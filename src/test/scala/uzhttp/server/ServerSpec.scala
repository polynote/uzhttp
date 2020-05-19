package uzhttp.server

import java.net.InetSocketAddress
import java.security.MessageDigest

import zio.{Chunk, Queue, Ref, Task, ZIO}
import zio.stream.{Sink, Stream, ZStream}, ZStream.Take
import org.scalatest.BeforeAndAfterAll
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import sttp.client.{Response => _, _}
import sttp.client.asynchttpclient.WebSocketHandler
import sttp.client.asynchttpclient.zio._
import sttp.client.ws.WebSocketEvent
import sttp.model.ws.WebSocketFrame
import uzhttp.{HTTPError, Request, Response, websocket}
import websocket.Binary

class ServerSpec extends AnyFreeSpec with Matchers with BeforeAndAfterAll {
  import TestRuntime.runtime.unsafeRun
  private val runningServerRef: Ref[Option[Server]] = unsafeRun(Ref.make(None))
  private implicit val backend: SttpBackend[Task, Nothing, WebSocketHandler] = unsafeRun(AsyncHttpClientZioBackend())

  private val serverTask = unsafeRun {
    val managed = Server.builder(new InetSocketAddress("127.0.0.1", 0))
      .handleAll {
        case req@Request.WebsocketRequest(_, _, _, _, frames) =>
          Queue.unbounded[Take[Throwable, websocket.Frame]].flatMap {
            output => Response.websocket(
              req,
              Stream.fromQueue(output).collectWhileSuccess.flattenChunks
            ).tap {
              socket =>
                frames.map {
                  case Binary(data, isLast) if data.length >= 10240 =>
                    // the STTP client (netty-based) can't handle large frames in response
                    // so digest instead (can't figure out how to configure that)
                    Binary(md5(data), isLast)
                  case frame => frame
                }.into(output).fork.unit
            }
          }

        case req =>
          if (req.headers.get("Content-Length").map(_.toInt).exists(_ > 0)) {
            req.body match {
              case Some(body) =>
                body.runCollect.map {
                  bytes =>
                    Response.const(
                      Chunk.fromIterable(bytes).toArray,
                      headers = req.headers.toList.map {
                        case (name, value) => s"x-echoed-$name" -> value
                      } ++ List("x-echoed-request-uri" -> req.uri.getPath, "x-echoed-method" -> req.method.name, "Content-Type" -> "application/octet-stream")
                    )
                }
              case None =>
                ZIO.fail(HTTPError.BadRequest("Request has no body!"))
            }
          } else {
            ZIO.succeed {
              Response.plain(
                s"${req.method.name} ${req.uri} HTTP/${req.version.string}\r\n${req.headers.toList.map { case (k,v) => s"$k: $v" }.mkString("\r\n")}",
                headers = req.headers.toList.map {
                  case (name, value) => s"x-echoed-$name" -> value
                } ++ List("x-echoed-request-uri" -> req.uri.getPath, "x-echoed-method" -> req.method.name)
              )
            }
          }

      }.errorResponse {
        err =>
          ZIO.succeed(Response.plain(s"HTTP Error: ${err.statusCode} ${err.statusText}", err))
      }.serve

      managed.tapM(server => runningServerRef.set(Some(server))).useForever.forkDaemon
  }

  private val runningServer: Server = unsafeRun(runningServerRef.get.doUntil(_.nonEmpty).flatMap(server => server.get.awaitUp.as(server.get)))

  private val port = unsafeRun(runningServer.localAddress).asInstanceOf[InetSocketAddress].getPort

  "Server" - {

    "Handles basic requests" - {
      "GET" in {
        unsafeRun {
          basicRequest.get(uri"http://localhost:$port/basicReq").send().flatMap {
            rep => ZIO.effect {
              rep.code.code mustEqual 200
              val headers = rep.headers.map(h => h.name.toLowerCase -> h.value).toMap
              headers("x-echoed-request-uri") mustEqual "/basicReq"
              headers("x-echoed-method") mustEqual "GET"
            }
          }
        }
      }

      "POST" - {
        "small body" in {
          val body = Array.tabulate(256)(_.toByte)
          unsafeRun {
            basicRequest.post(uri"http://localhost:$port/basicReq").body(body).response(asByteArrayAlways).send().flatMap {
              rep => ZIO.effect {
                rep.code.code mustEqual 200
                rep.body must contain theSameElementsInOrderAs body
              }
            }
          }
        }

        "large body" in {
          val body = Array.tabulate(Short.MaxValue)(_.toByte)
          unsafeRun {
            basicRequest.post(uri"http://localhost:$port/basicReq").body(body).response(asByteArrayAlways).send().flatMap {
              rep => ZIO.effect {
                rep.code.code mustEqual 200
                rep.body must contain theSameElementsInOrderAs body
              }
            }
          }
        }
      }
    }

    "Decodes URI" in {
      unsafeRun {
        basicRequest.get(uri"http://localhost:$port/basic%20request").send().flatMap {
          rep => ZIO.effect {
            rep.code.code mustEqual 200
            val headers = rep.headers.map(h => h.name.toLowerCase -> h.value).toMap
            headers("x-echoed-request-uri") mustEqual "/basic request"
            headers("x-echoed-method") mustEqual "GET"
          }
        }
      }
    }

    "handles websocket request" in {
      val smallBinaryData = Array.tabulate(125)(i => i.toByte)  // covers no length indicatro
      val binaryData = Array.tabulate(256)(i => i.toByte)       // covers length indicator 126, length < 32767
      val bigBinaryData = new Array[Byte](Short.MaxValue + 50)  // covers length indicator 126, length > 32767
      val hugeBinaryData = new Array[Byte](Short.MaxValue + Short.MaxValue + 2) // covers length indicator 127
      scala.util.Random.nextBytes(bigBinaryData)
      val strData = "This is a string to the websocket"

      def errOnClose[A](zio: Task[Either[WebSocketEvent.Close, A]]): Task[A] =
        zio.flatMap(ei => ZIO.fromEither(ei).orElseFail(new Exception("Websocket closed early")))

      unsafeRun {
        for {
          received        <- Queue.unbounded[Any]
          response        <- basicRequest.get(uri"ws://localhost:$port/websocketTest").openWebsocketF(ZioWebSocketHandler())
          sock             = response.result
          _               <- sock.send(WebSocketFrame.binary(smallBinaryData))
          smallBinaryEcho <- errOnClose(sock.receiveBinary(true))
          _               <- sock.send(WebSocketFrame.binary(binaryData))
          binaryEcho      <- errOnClose(sock.receiveBinary(true))
          _               <- sock.send(WebSocketFrame.binary(bigBinaryData))
          bigBinaryEcho   <- errOnClose(sock.receiveBinary(true))
          _               <- sock.send(WebSocketFrame.binary(hugeBinaryData))
          hugeBinaryEcho  <- errOnClose(sock.receiveBinary(true))
          _               <- sock.send(WebSocketFrame.text(strData))
          stringEcho      <- errOnClose(sock.receiveText(true))
          _               <- sock.send(WebSocketFrame.close)
        } yield {
          smallBinaryEcho must contain theSameElementsInOrderAs smallBinaryData
          binaryEcho must contain theSameElementsInOrderAs binaryData
          bigBinaryEcho must contain theSameElementsInOrderAs md5(bigBinaryData)
          hugeBinaryEcho must contain theSameElementsInOrderAs md5(hugeBinaryData)
          stringEcho mustEqual strData
        }
      }
    }

  }

  private def md5(data: Array[Byte]): Array[Byte] = MessageDigest.getInstance("MD5").digest(data)

  override def afterAll(): Unit = {
    unsafeRun(runningServer.shutdown())
  }

}