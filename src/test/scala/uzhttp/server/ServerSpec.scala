package uzhttp.server

import java.net.InetSocketAddress

import zio.{Chunk, Queue, Ref, Task, ZIO}
import zio.stream.{Sink, Stream, Take}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import sttp.client.{Response => _, _}
import sttp.client.asynchttpclient.WebSocketHandler
import sttp.client.asynchttpclient.zio._
import sttp.model.ws.WebSocketFrame

class ServerSpec extends AnyFreeSpec with Matchers with BeforeAndAfterAll {
  import TestRuntime.runtime.unsafeRun
  private val runningServerRef: Ref[Option[Server]] = unsafeRun(Ref.make(None))
  private implicit val backend: SttpBackend[Task, Nothing, WebSocketHandler] = unsafeRun(AsyncHttpClientZioBackend())

  private val serverTask = unsafeRun {
    val managed = Server.builder(new InetSocketAddress("127.0.0.1", 0))
      .handleAll {
        case req@Request.WebsocketRequest(_, _, _, _, frames) =>
          Queue.unbounded[Take[Throwable, Websocket.Frame]].flatMap {
            output => Response.websocket(
              req,
              Stream.fromQueue(output).unTake
            ).tap {
              socket => frames.into(output).fork.unit
            }
          }

        case req =>
          if (req.headers.get("Content-Length").map(_.toInt).exists(_ > 0)) {
            req.body match {
              case Some(body) =>
                body.chunks.tap {
                  chunk =>
                    ZIO.effectTotal(println(s"Chunk: ${chunk.length}"))
                }.runCollect.map {
                  bytes =>
                    Response.const(
                      Chunk.fromIterable(bytes).flatten.toArray,
                      headers = req.headers.toList.map {
                        case (name, value) => s"x-echoed-$name" -> value
                      } ++ List("x-echoed-request-uri" -> req.uri, "x-echoed-method" -> req.method.name, "Content-Type" -> "application/octet-stream")
                    )
                }
              case None =>
                ZIO.fail(BadRequest("Request has no body!"))
            }
          } else {
            ZIO.succeed {
              Response.plain(
                s"${req.method.name} ${req.uri} HTTP/${req.version.string}\r\n${req.headers.toList.map { case (k,v) => s"$k: $v" }.mkString("\r\n")}",
                headers = req.headers.toList.map {
                  case (name, value) => s"x-echoed-$name" -> value
                } ++ List("x-echoed-request-uri" -> req.uri, "x-echoed-method" -> req.method.name)
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

    "handles websocket request" in {
      val binaryData = Array.tabulate(256)(i => i.toByte)
      val strData = "This is a string to the websocket"

      unsafeRun {
        for {
          received   <- Queue.unbounded[Any]
          response   <- basicRequest.get(uri"ws://localhost:$port/websocketTest").openWebsocketF(ZioWebSocketHandler())
          sock        = response.result
          _          <- sock.send(WebSocketFrame.binary(binaryData))
          binaryEcho <- sock.receiveBinary(true).flatMap(ei => ZIO.fromEither(ei)).mapError(_ => new Exception("Websocket closed early"))
          _          <- sock.send(WebSocketFrame.text(strData))
          stringEcho <- sock.receiveText(true).flatMap(ei => ZIO.fromEither(ei)).mapError(_ => new Exception("Websocket closed early"))
          _          <- sock.send(WebSocketFrame.close)
        } yield {
          binaryEcho must contain theSameElementsInOrderAs binaryEcho
          stringEcho mustEqual strData
        }
      }
    }

  }

  override def afterAll(): Unit = {
    unsafeRun(runningServer.shutdown())
  }

}