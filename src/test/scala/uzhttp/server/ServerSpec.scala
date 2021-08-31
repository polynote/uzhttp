package uzhttp.server

import java.net.InetSocketAddress
import java.security.MessageDigest

import zio.{Chunk, Queue, Ref, Task, ZIO}
import zio.stream.{Sink, Stream, ZStream}
import ZStream.Take
import org.asynchttpclient.DefaultAsyncHttpClientConfig
import org.scalatest.BeforeAndAfterAll
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import uzhttp.{HTTPError, Request, Response, websocket}
import websocket.Binary
import sttp.client3.SttpBackend
import sttp.client3._
import sttp.client3.asynchttpclient.zio.AsyncHttpClientZioBackend
import sttp.client3.internal.ws.WebSocketEvent
import sttp.capabilities.WebSockets
import sttp.client3.impl.zio.ZioWebSockets
import sttp.monad.syntax.MonadErrorOps
import sttp.ws.WebSocketFrame
import sttp.client3.asynchttpclient.zio._
import sttp.ws.WebSocket
import sttp.capabilities.zio.ZioStreams
import org.scalatest.compatible.Assertion

class ServerSpec extends AnyFreeSpec with Matchers with BeforeAndAfterAll {
  import TestRuntime.runtime.unsafeRun
  private val runningServerRef: Ref[Option[Server]] = unsafeRun(Ref.make(None))

  private val backend: SttpBackend[Task, WebSockets] = unsafeRun(
    AsyncHttpClientZioBackend.usingConfig(
      new DefaultAsyncHttpClientConfig.Builder()
        .setWebSocketMaxFrameSize(Int.MaxValue)
        .setWebSocketMaxBufferSize(8192)
        .build()
    )
  )

  private val serverTask = unsafeRun {
    val managed = Server
      .builder(new InetSocketAddress("127.0.0.1", 0))
      .handleAll {
        case req @ Request.WebsocketRequest(_, _, _, _, frames) =>
          Response.websocket(
            req,
            frames.flatMap { frame =>
              Stream(frame, frame)
            }
          )

        case req =>
          if (req.headers.get("Content-Length").map(_.toInt).exists(_ > 0)) {
            req.body match {
              case Some(body) =>
                body.runCollect.map { bytes =>
                  Response.const(
                    Chunk.fromIterable(bytes).toArray,
                    headers = req.headers.toList.map { case (name, value) =>
                      s"x-echoed-$name" -> value
                    } ++ List(
                      "x-echoed-request-uri" -> req.uri.getPath,
                      "x-echoed-method" -> req.method.name,
                      "Content-Type" -> "application/octet-stream"
                    )
                  )
                }
              case None =>
                ZIO.fail(HTTPError.BadRequest("Request has no body!"))
            }
          } else {
            ZIO.succeed {
              Response.plain(
                s"${req.method.name} ${req.uri} HTTP/${req.version.string}\r\n${req.headers.toList
                  .map { case (k, v) => s"$k: $v" }
                  .mkString("\r\n")}",
                headers = req.headers.toList.map { case (name, value) =>
                  s"x-echoed-$name" -> value
                } ++ List(
                  "x-echoed-request-uri" -> req.uri.getPath,
                  "x-echoed-method" -> req.method.name
                )
              )
            }
          }

      }
      .errorResponse { err =>
        ZIO.succeed(
          Response
            .plain(s"HTTP Error: ${err.statusCode} ${err.statusText}", err)
        )
      }
      .serve

    managed
      .tapM(server => runningServerRef.set(Some(server)))
      .useForever
      .forkDaemon
  }

  private val runningServer: Server = unsafeRun(
    runningServerRef.get
      .repeatUntil(_.nonEmpty)
      .flatMap(server => server.get.awaitUp.as(server.get))
  )

  private val port = unsafeRun(runningServer.localAddress)
    .asInstanceOf[InetSocketAddress]
    .getPort

  "Server" - {

    "Handles basic requests" - {
      "GET" in {
        unsafeRun {
          basicRequest
            .get(uri"http://localhost:$port/basicReq")
            .send(backend)
            .flatMap { rep =>
              ZIO.effect {
                rep.code.code mustEqual 200
                val headers =
                  rep.headers.map(h => h.name.toLowerCase -> h.value).toMap
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
            basicRequest
              .post(uri"http://localhost:$port/basicReq")
              .body(body)
              .response(asByteArrayAlways)
              .send(backend)
              .flatMap { rep =>
                ZIO.effect {
                  rep.code.code mustEqual 200
                  rep.body must contain theSameElementsInOrderAs body
                }
              }
          }
        }

        "large body" in {
          val body = Array.tabulate(Short.MaxValue)(_.toByte)
          unsafeRun {
            basicRequest
              .post(uri"http://localhost:$port/basicReq")
              .body(body)
              .response(asByteArrayAlways)
              .send(backend)
              .flatMap { rep =>
                ZIO.effect {
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
        basicRequest
          .get(uri"http://localhost:$port/basic%20request")
          .send(backend)
          .flatMap { rep =>
            ZIO.effect {
              rep.code.code mustEqual 200
              val headers =
                rep.headers.map(h => h.name.toLowerCase -> h.value).toMap
              headers("x-echoed-request-uri") mustEqual "/basic request"
              headers("x-echoed-method") mustEqual "GET"
            }
          }
      }
    }

    "handles websocket request" in {
      val smallBinaryData =
        Array.tabulate(64)(i => i.toByte) // covers no length indicator
      val binaryData = Array.tabulate(256)(i =>
        i.toByte
      ) // covers length indicator 126, length < 32767
      val bigBinaryData =
        new Array[Byte](
          Short.MaxValue + 50
        ) // covers length indicator 126, length > 32767
      val hugeBinaryData =
        new Array[Byte](
          Short.MaxValue + Short.MaxValue + 2
        ) // covers length indicator 127
      scala.util.Random.nextBytes(bigBinaryData)
      val strData = "This is a string to the websocket"

      def errOnClose[A](zio: Task[A]): Task[A] =
        zio.mapError(e =>
          new Exception(s"Websocket closed early ${e.getMessage}")
        )

      unsafeRun {
        sendR(
          basicRequest
            .get(uri"ws://localhost:$port/websocketTest")
            .response(
              asWebSocketAlways[Task, Assertion](ws =>
                for {
                  _ <- ws.send(WebSocketFrame.binary(smallBinaryData))
                  small1 <- errOnClose(ws.receiveBinary(true))
                  small2 <- errOnClose(ws.receiveBinary(true))
                  _ <- ws.send(WebSocketFrame.binary(binaryData))
                  mid1 <- errOnClose(ws.receiveBinary(true))
                  mid2 <- errOnClose(ws.receiveBinary(true))
                  _ <- ws.send(WebSocketFrame.binary(bigBinaryData))
                  big1 <- errOnClose(ws.receiveBinary(true))
                  big2 <- errOnClose(ws.receiveBinary(true))
                  _ <- ws.send(WebSocketFrame.binary(hugeBinaryData))
                  huge1 <- errOnClose(ws.receiveBinary(true))
                  huge2 <- errOnClose(ws.receiveBinary(true))
                  _ <- ws.send(WebSocketFrame.text(strData))
                  string1 <- errOnClose(ws.receiveText(true))
                  string2 <- errOnClose(ws.receiveText(true))
                  _ <- ws.send(WebSocketFrame.close)
                } yield {
                  small1 must contain theSameElementsInOrderAs smallBinaryData
                  small2 must contain theSameElementsInOrderAs smallBinaryData
                  mid1 must contain theSameElementsInOrderAs binaryData
                  mid2 must contain theSameElementsInOrderAs binaryData
                  big1 must contain theSameElementsInOrderAs bigBinaryData
                  big2 must contain theSameElementsInOrderAs bigBinaryData
                  huge1 must contain theSameElementsInOrderAs hugeBinaryData
                  huge2 must contain theSameElementsInOrderAs hugeBinaryData
                  string1 mustEqual strData
                  string2 mustEqual strData
                }
              )
            )
        ).provideLayer(
          AsyncHttpClientZioBackend.layerUsingConfigBuilder(builder =>
            builder
              .setWebSocketMaxFrameSize(Int.MaxValue)
              .setWebSocketMaxBufferSize(8192)
          ) +!+ zio.ZEnv.live
        )
      }
    }
  }

  private def md5(data: Array[Byte]): Array[Byte] =
    MessageDigest.getInstance("MD5").digest(data)

  override def afterAll(): Unit = {
    unsafeRun(runningServer.shutdown())
  }
}
