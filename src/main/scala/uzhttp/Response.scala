package uzhttp

import java.io.InputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.{Charset, StandardCharsets}
import java.nio.file.{Files, Path, Paths, StandardOpenOption}
import java.security.MessageDigest
import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneOffset, ZonedDateTime}
import java.util.Base64

import uzhttp.header.Headers
import uzhttp.server.Server
import uzhttp.HTTPError.{BadRequest, NotFound}
import uzhttp.websocket.Frame
import zio.ZIO.{effect, effectTotal}
import zio.blocking.{Blocking, effectBlocking}
import zio.stream.Stream
import zio.{Chunk, IO, Promise, RIO, Task, UIO, URIO, ZIO, ZManaged}

trait Response {
  def headers: Headers
  def status: Status

  def addHeaders(headers:(String, String)*): Response
  def addHeader(name: String, value: String): Response = addHeaders((name, value))

  private[uzhttp] def writeTo(connection: Server.ConnectionWriter): ZIO[Blocking, Throwable, Unit]
  private[uzhttp] def closeAfter: Boolean = headers.exists {
    case (k, v) => k.toLowerCase == "connection" && v.toLowerCase == "close"
  }
}

object Response {
  def plain(body: String, status: Status = Status.Ok, headers: List[(String, String)] = Nil, charset: Charset = StandardCharsets.UTF_8): Response =
    const(body.getBytes(charset), status, contentType = s"text/plain; charset=${charset.name()}", headers = headers)

  def html(body: String, status: Status = Status.Ok, headers: List[(String, String)] = Nil, charset: Charset = StandardCharsets.UTF_8): Response =
    const(body.getBytes(charset), status, contentType = s"text/html; charset=${charset.name()}", headers = headers)

  def const(body: Array[Byte], status: Status = Status.Ok, contentType: String = "application/octet-stream", headers: List[(String, String)] = Nil): Response =
    ConstResponse(status, body, repHeaders(contentType, body.length, headers))

  private def getModifiedTime(path: Path): RIO[Blocking, Instant] =
    effectBlocking(Files.getLastModifiedTime(path).toInstant)

  private def localPath(uri: URI): URIO[Blocking, Option[Path]] = uri match {
    case uri if uri.getScheme == "file" => effectBlocking(Paths.get(uri)).option
    case uri if uri.getScheme == "jar"  => effect(new URI(uri.getSchemeSpecificPart.takeWhile(_ != '!'))).flatMap(localPath).orElseSucceed(None)
    case _ => ZIO.succeed(None)
  }

  private def checkModifiedSince(path: Path, ifModifiedSince: Option[String]): ZIO[Blocking, Unit, Response] = ZIO.fromOption {
    ifModifiedSince.map {
      dateStr =>
        effect(ZonedDateTime.parse(dateStr, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant).orElseFail(()).flatMap {
          ifModifiedSinceInstant =>
            getModifiedTime(path).orElseFail(()).flatMap {
              case mtime if mtime.isAfter(ifModifiedSinceInstant) => ZIO.fail(())
              case _ => ZIO.succeed(Response.const(Array.emptyByteArray, Status.NotModified))
            }
        }
    }
  }.flatten

  private def formatInstant(instant: Instant): String =
    DateTimeFormatter.RFC_1123_DATE_TIME.format(instant.atOffset(ZoneOffset.UTC))

  private def checkExists(path: Path, uri: String): ZIO[Blocking, NotFound, Unit] =
    effectBlocking(Option(path.toFile.exists()).filter(identity)).orDie.someOrFail(NotFound(uri)).unit
  /**
    * Read a response from a path. Uses blocking I/O, so that a file on the local filesystem can be directly
    * transferred to the connection using OS-level primitives when possible.
    *
    * @param path             A Path pointing to the file on the filesystem.
    * @param request          The request to respond to. This is used:
    *                           - To check if the `If-Modified-Since` header value included in the request for this file.
    *                             If given (in RFC 1123 format), an attempt will be made to determine if the file has been
    *                             modified since the requested timestamp. If it hasn't, then the response returned will be
    *                             a 304 Not Modified response with no body.
    *                           - To provide the URI for a NotFound error, in case the path does not exist.
    * @param contentType      The `Content-Type` header to use for the response. Defaults to `application/octet-stream`.
    * @param status           The status of the response. Defaults to `Ok` (HTTP 200)
    * @param headers          Any additional headers to include in the response.
    * @return A ZIO value which, when evaluated, will attempt to locate the given resource and provide an appropriate
    *         [[Response]]. If the resource isn't present, it will fail with [[NotFound]]. Since this response interacts
    *         with the filesystem, it can fail with other arbitrary Throwable failures; you'll probably need to
    *         catch these and convert them to [[HTTPError]] failures.
    */
  def fromPath(path: Path, request: Request, contentType: String = "application/octet-stream", status: Status = Status.Ok, headers: List[(String, String)] = Nil): ZIO[Blocking, Throwable, Response] =
    checkExists(path, request.uri) *> checkModifiedSince(path, request.headers.get("If-Modified-Since")) orElse {
      for {
        size     <- effectBlocking(path.toFile.length())
        modified <- getModifiedTime(path).map(formatInstant).option
      } yield PathResponse(status, path, size, modified.map("Modified" -> _).toList ::: repHeaders(contentType, size, headers))
    }


  /**
    * Read a response from a resource. Uses blocking I/O, so that a file on the local filesystem can be directly
    * transferred to the connection using OS-level primitives when possible.
    *
    * @param name             The name (path) of the resource
    * @param request          The request to respond to. This is used:
    *                           - To check if the `If-Modified-Since` header value included in the request for this file.
    *                             If given (in RFC 1123 format), an attempt will be made to determine if the file has been
    *                             modified since the requested timestamp. If it hasn't, then the response returned will be
    *                             a 304 Not Modified response with no body.
    *                           - To provide the URI for a NotFound error, in case the path does not exist.
    * @param classLoader      The class loader which can find the resource (defaults to this class's class loader)
    * @param contentType      The `Content-Type` header to use for the response. Defaults to `application/octet-stream`.
    * @param status           The status of the response. Defaults to `Ok` (HTTP 200)
    * @param headers          Any additional headers to include in the response.
    * @return A ZIO value which, when evaluated, will attempt to locate the given resource and provide an appropriate
    *         [[Response]]. If the resource isn't present, it will fail with [[NotFound]]. Since this response interacts
    *         with the filesystem, it can fail with other arbitrary Throwable failures; you'll probably need to
    *         catch these and convert them to [[HTTPError]] failures.
    */
  def fromResource(
    name: String,
    request: Request,
    classLoader: ClassLoader = getClass.getClassLoader,
    contentType: String = "application/octet-stream",
    status: Status = Status.Ok,
    headers: List[(String, String)] = Nil
  ): ZIO[Blocking, Throwable, Response] = effectBlocking(Option(classLoader.getResource(name)))
    .someOrFail(NotFound(request.uri))
    .flatMap {
      resource =>
        localPath(resource.toURI).get.tap(checkExists(_, request.uri)).flatMap(path => checkModifiedSince(path, request.headers.get("If-Modified-Since"))) orElse {
          resource match {
            case url if url.getProtocol == "file" =>
              for {
                path     <- effectBlocking(Paths.get(url.toURI))
                modified <- getModifiedTime(path).map(formatInstant)
                size     <- effectBlocking(Files.size(path))
              } yield PathResponse(status, path, size, ("Modified" -> modified) :: repHeaders(contentType, size, headers))
            case url =>
              for {
                conn     <- effectBlocking(url.openConnection())
                _        <- effectBlocking(conn.connect())
                modified  = Option(conn.getLastModified).map(Instant.ofEpochMilli).map(formatInstant)
                size     <- effectBlocking(conn.getContentLengthLong)
                rep      <- fromInputStream(
                  effectBlocking(conn.getInputStream).toManaged(is => effectTotal(is.close())),
                  size = size,
                  status = status,
                  headers = modified.map("Modified" -> _).toList ::: repHeaders(contentType, size, headers))
              } yield rep
          }
        }
    }

  def fromInputStream(
    stream: ZManaged[Blocking, Throwable, InputStream],
    size: Long,
    contentType: String = "application/octet-stream",
    status: Status = Status.Ok,
    ifModifiedSince: Option[String] = None,
    headers: List[(String, String)] = Nil
  ): UIO[Response] = ZIO.succeed(InputStreamResponse(status, stream, size, repHeaders(contentType, size, headers)))

  def fromStream(stream: Stream[Nothing, Chunk[Byte]], size: Long, contentType: String = "application/octet-stream", status: Status = Status.Ok, ifModifiedSince: Option[String] = None, headers: List[(String, String)] = Nil): UIO[Response] =
    ZIO.succeed(ByteStreamResponse(status, stream.map(_.toArray), repHeaders(contentType, size, headers)))

  def websocket(req: Request, output: Stream[Throwable, Frame]): IO[BadRequest, WebsocketResponse] = {
    val handshakeHeaders = ZIO.effectTotal(req.headers.get("Sec-WebSocket-Key")).someOrFail(BadRequest("Missing Sec-WebSocket-Key")).map {
      acceptKey =>
        val acceptHash = Base64.getEncoder.encodeToString {
          MessageDigest.getInstance("SHA-1")
            .digest((acceptKey ++ "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII))
        }
        List("Upgrade" -> "websocket", "Connection" -> "upgrade", "Sec-WebSocket-Accept" -> acceptHash)
    }

    for {
      closed  <- Promise.make[Throwable, Unit]
      headers <- handshakeHeaders
    } yield WebsocketResponse(output, closed, headers)
  }

  private def repHeaders(contentType: String, contentLength: Long, headers: List[(String, String)]): List[(String, String)] =
    ("Content-Type" -> contentType) :: ("Content-Length" -> contentLength.toString) :: headers

  private[uzhttp] def headerBytes(response: Response): Array[Byte] = {
    val statusLine = s"HTTP/1.1 ${response.status.statusCode} ${response.status.statusText}\r\n"
    val headers = response.headers.map {
      case (name, value) => s"$name: $value\r\n"
    }.mkString

    (statusLine + headers + "\r\n").getBytes(StandardCharsets.US_ASCII)
  }

  private final case class ByteStreamResponse private[uzhttp](
    status: Status,
    body: Stream[Nothing, Array[Byte]],
    headers: Headers
  ) extends Response {
    override def addHeaders(headers: (String, String)*): ByteStreamResponse = copy(headers = this.headers ++ headers)

    override private[uzhttp] def writeTo(connection: Server.ConnectionWriter): ZIO[Blocking, Throwable, Unit] =
      connection.writeByteArrays(body)
  }

  private final case class ConstResponse private[uzhttp] (
    status: Status,
    body: Array[Byte],
    headers: Headers
  ) extends Response {
    override def addHeaders(headers: (String, String)*): ConstResponse = copy(headers = this.headers ++ headers)

    override private[uzhttp] def writeTo(connection: Server.ConnectionWriter): ZIO[Blocking, Throwable, Unit] =
      connection.writeByteArrays(Stream(Response.headerBytes(this), body))
  }

  private final case class PathResponse private[uzhttp] (
    status: Status,
    path: Path,
    size: Long,
    headers: Headers
  ) extends Response {
    override def addHeaders(headers: (String, String)*): Response = copy(headers = this.headers ++ headers)

    override private[uzhttp] def writeTo(connection: Server.ConnectionWriter): ZIO[Blocking, Throwable, Unit] = {
      effectBlocking(FileChannel.open(path, StandardOpenOption.READ)).toManaged(chan => effectTotal(chan.close())).use {
        chan => connection.transferFrom(ByteBuffer.wrap(Response.headerBytes(this)), chan)
      }

    }
  }

  private final case class InputStreamResponse private[uzhttp](
    status: Status,
    getInputStream: ZManaged[Blocking, Throwable, InputStream],
    size: Long,
    headers: Headers
  ) extends Response {
    override def addHeaders(headers: (String, String)*): Response = copy(headers = this.headers ++ headers)
    override private[uzhttp] def writeTo(connection: Server.ConnectionWriter): ZIO[Blocking, Throwable, Unit] =
      getInputStream.use {
        is => connection.pipeFrom(ByteBuffer.wrap(Response.headerBytes(this)), is, if (size < 8192) size.toInt else 8192)
      }
  }

  final case class WebsocketResponse private[uzhttp](
    frames: Stream[Throwable, Frame],
    closed: Promise[Throwable, Unit],
    headers: Headers
  ) extends Response {

    override val status: Status = Status.SwitchingProtocols
    override def addHeaders(headers: (String, String)*): Response = copy(headers = this.headers ++ headers)
    override private[uzhttp] val closeAfter = true

    override private[uzhttp] def writeTo(connection: Server.ConnectionWriter): ZIO[Blocking, Throwable, Unit] = {
      connection.writeByteBuffers(Stream(ByteBuffer.wrap(Response.headerBytes(this))) ++ frames.map(_.toBytes))
    }
  }

}


