package uzhttp.routing

import java.net.InetSocketAddress

import org.scalatest.BeforeAndAfterAll
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import sttp.client.asynchttpclient.WebSocketHandler
import sttp.client.asynchttpclient.zio.AsyncHttpClientZioBackend
import sttp.client.{SttpBackend, Response => _, _}
import uzhttp.Response
import uzhttp.server.Server
import zio.{Ref, Task, ZIO}

class RoutingSpec extends AnyFreeSpec with Matchers with BeforeAndAfterAll {
  import uzhttp.server.TestRuntime.runtime.unsafeRun
  private val runningServerRef: Ref[Option[Server]] = unsafeRun(Ref.make(None))
  private implicit val backend: SttpBackend[Task, Nothing, WebSocketHandler] = unsafeRun(AsyncHttpClientZioBackend())

  private val serverTask = unsafeRun {
    val managed = Server.builder(new InetSocketAddress("127.0.0.1", 0))
      .handleSome {
        get {
          path("/") { case req =>
            ZIO.succeed(Response.plain("Hello, World!"))
          } ~
          path("/test" / ? / "more") { case (req, a) =>
            ZIO.succeed(Response.plain(a))
          } ~
          path("/test2" / ? / Remaining) { case (req, a, b) =>
            ZIO.succeed(Response.plain(a + "-" + b))
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

  "Path DSL" - {
    "Handles index request" in {
      unsafeRun {
        basicRequest.get(uri"http://localhost:$port").send().flatMap {
          rep => ZIO.effect {
            rep.code.code mustEqual 200
            rep.body mustBe Right("Hello, World!")
          }
        }
      }
    }

    "Handles single parameter request" in {
      unsafeRun {
        basicRequest.get(uri"http://localhost:$port/test/foobar/more").send().flatMap {
          rep => ZIO.effect {
            rep.code.code mustEqual 200
            rep.body mustBe Right("foobar")
          }
        }
      }
    }

    "Handles single parameter with remainingrequest" in {
      unsafeRun {
        basicRequest.get(uri"http://localhost:$port/test2/foobar/more/stuff/here").send().flatMap {
          rep => ZIO.effect {
            rep.code.code mustEqual 200
            rep.body mustBe Right("foobar-more/stuff/here")
          }
        }
      }
    }

  }


}
