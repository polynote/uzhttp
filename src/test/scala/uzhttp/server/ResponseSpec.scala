package uzhttp.server

import java.io.{FileOutputStream, InputStream}
import java.net.URLClassLoader
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.time.{Instant, ZoneOffset}
import java.time.format.DateTimeFormatter
import java.time.temporal.{TemporalAccessor, TemporalQuery}
import java.util.concurrent.atomic.AtomicReference
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import zio.{Chunk, Ref, Task, ZIO, stream}
import ZIO.effect
import org.scalatest.Assertion
import uzhttp.{Request, Response}
import uzhttp.header.Headers
import zio.blocking.Blocking

class ResponseSpec extends AnyFreeSpec with Matchers {
  import TestRuntime.runtime.unsafeRun


  private def splitRequest(req: Chunk[Byte]) = {
    val arr = req.toArray
    val splitPoint = arr.indexOfSlice(Seq('\r', '\n', '\r', '\n'))
    val headerLines = new String(arr, 0, splitPoint, StandardCharsets.US_ASCII).linesWithSeparators.map(_.stripLineEnd).toList
    (headerLines.head, Headers.fromLines(headerLines.tail), req.drop(splitPoint + 4))
  }

  private def verify(rep: Response)(fn: (String, Headers, Chunk[Byte]) => Assertion): Assertion = {
    val conn = MockConnectionWriter()
    unsafeRun(rep.writeTo(conn))
    val (status, headers, body) = splitRequest(unsafeRun(conn.writtenBytes.get))
    fn(status, headers, body)
  }

  private val toInstant: TemporalQuery[Instant] = new TemporalQuery[Instant] {
    override def queryFrom(temporal: TemporalAccessor): Instant = Instant.from(temporal)
  }

  private def timeStr(inst: Instant): String = DateTimeFormatter.RFC_1123_DATE_TIME.format(inst.atZone(ZoneOffset.UTC))
  private def parseTime(str: String): Instant = DateTimeFormatter.RFC_1123_DATE_TIME.parse(str, toInstant)
  private def trunc(inst: Instant): Instant = parseTime(timeStr(inst))
  private val currentTimeStr: String = timeStr(Instant.now())

  "Constant response" - {
    "writes to connection" in {
      val bytes = Array.tabulate(256)(_.toByte)
      verify(Response.const(bytes, headers = (List("Flerg" -> "Blerg")))) {
        (status, headers, body) =>
          status mustEqual "HTTP/1.1 200 OK"
          headers("Flerg") mustEqual "Blerg"
          headers("Content-Length") mustEqual "256"
          body.toArray must contain theSameElementsInOrderAs bytes
      }
    }
  }

  "Path response" - {
    val path = Paths.get(getClass.getClassLoader.getResource("path-test.txt").toURI)
    val modified = Files.getLastModifiedTime(path).toInstant
    val expected = Files.readAllBytes(path)

    val req = Request.NoBody(Request.GET, "/path-test.txt", Request.Http11, Headers.empty)

    "writes to connection" in {
      verify(unsafeRun(Response.fromPath(path, req, contentType = "text/plain", headers = List("Flerg" -> "Blerg")))) {
        (status, headers, body) =>
          status mustEqual "HTTP/1.1 200 OK"
          headers("Flerg") mustEqual "Blerg"
          headers("Content-Length") mustEqual expected.length.toString
          headers("Content-Type") mustEqual "text/plain"
          parseTime(headers("Modified")) mustEqual trunc(modified)
          body.toArray must contain theSameElementsInOrderAs expected
      }
    }

    "respects if-modified-since" - {
      "when it is after the modification date" in {
        verify(unsafeRun(Response.fromPath(path, req.addHeader("If-Modified-Since", currentTimeStr)))) {
          (status, headers, body) =>
            status mustEqual "HTTP/1.1 304 Not Modified"
            headers("Content-Length") mustEqual "0"
            assert(body.isEmpty)
        }
      }

      "when it is before the modification date" in {
        verify(unsafeRun(Response.fromPath(path, req.addHeader("If-Modified-Since", timeStr(modified.minusSeconds(5)))))) {
          (status, headers, body) =>
            status mustEqual "HTTP/1.1 200 OK"
            headers("Content-Length") mustEqual expected.length.toString
            body.toArray must contain theSameElementsInOrderAs expected
        }
      }
    }
  }

  "Resource response" - {
    val resource = "path-test.txt"
    val path = Paths.get(getClass.getClassLoader.getResource(resource).toURI)
    val modified = Files.getLastModifiedTime(path).toInstant
    val expected = Files.readAllBytes(path)
    val req = Request.NoBody(Request.GET, "/path-test.txt", Request.Http11, Headers.empty)

    "when resource is un-jarred" - {
      "writes to connection" in {
        verify(unsafeRun(Response.fromResource(resource, req, contentType = "text/plain", headers = List("Flerg" -> "Blerg")))) {
          (status, headers, body) =>
            status mustEqual "HTTP/1.1 200 OK"
            headers("Flerg") mustEqual "Blerg"
            headers("Content-Length") mustEqual expected.length.toString
            headers("Content-Type") mustEqual "text/plain"
            parseTime(headers("Modified")) mustEqual trunc(modified)
            body.toArray must contain theSameElementsInOrderAs expected
        }
      }

      "respects if-modified-since" - {
        "when it is after the modification date" in {
          verify(unsafeRun(Response.fromResource(resource, req.addHeader("If-Modified-Since", currentTimeStr)))) {
            (status, headers, body) =>
              status mustEqual "HTTP/1.1 304 Not Modified"
              headers("Content-Length") mustEqual "0"
              assert(body.isEmpty)
          }
        }

        "when it is before the modification date" in {
          verify(unsafeRun(Response.fromResource(resource, req.addHeader("If-Modified-Since", timeStr(modified.minusSeconds(5)))))) {
            (status, headers, body) =>
              status mustEqual "HTTP/1.1 200 OK"
              headers("Content-Length") mustEqual expected.length.toString
              body.toArray must contain theSameElementsInOrderAs expected
          }
        }
      }
    }

    "when resource is inside a JAR" - {
      val jarFile = Files.createTempFile("resources", ".jar")
      val stream = new JarOutputStream(new FileOutputStream(jarFile.toFile))
      try {
        stream.putNextEntry(new ZipEntry("path-test.txt"))
        stream.write(expected)
        stream.finish()
      } finally {
        stream.close()
      }

      jarFile.toFile.deleteOnExit()

      val jarModified = Files.getLastModifiedTime(jarFile).toInstant
      val cl = new URLClassLoader(Array(jarFile.toUri.toURL), null)

      "writes to connection" in {
        verify(unsafeRun(Response.fromResource(resource, req, classLoader = cl, contentType = "text/plain", headers = List("Flerg" -> "Blerg")))) {
          (status, headers, body) =>
            status mustEqual "HTTP/1.1 200 OK"
            headers("Flerg") mustEqual "Blerg"
            headers("Content-Length") mustEqual expected.length.toString
            headers("Content-Type") mustEqual "text/plain"
            parseTime(headers("Modified")) mustEqual trunc(jarModified)
            body.toArray must contain theSameElementsInOrderAs expected
        }
      }

      "respects if-modified-since" - {
        "when it is after the modification date" in {
          verify(unsafeRun(Response.fromResource(resource, req.addHeader("If-Modified-Since", timeStr(jarModified.plusSeconds(5))), classLoader = cl))) {
            (status, headers, body) =>
              status mustEqual "HTTP/1.1 304 Not Modified"
              headers("Content-Length") mustEqual "0"
              assert(body.isEmpty)
          }
        }

        "when it is before the modification date" in {
          verify(unsafeRun(Response.fromResource(resource, req.addHeader("If-Modified-Since", timeStr(jarModified.minusSeconds(5))), classLoader = cl))) {
            (status, headers, body) =>
              status mustEqual "HTTP/1.1 200 OK"
              headers("Content-Length") mustEqual expected.length.toString
              body.toArray must contain theSameElementsInOrderAs expected
          }
        }
      }
    }
  }
}
