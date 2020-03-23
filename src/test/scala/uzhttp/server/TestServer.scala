package uzhttp.server

import java.net.{InetSocketAddress, URLConnection}
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

import uzhttp.websocket.{Binary, Close, Continuation, Frame, Ping, Pong, Text}
import uzhttp.{HTTPError, Request, Response}
import zio.{RIO, UIO, ZIO}
import zio.blocking.Blocking
import zio.duration.Duration
import zio.internal.{Platform, Tracing}
import zio.stream.{Take, Stream}

object TestServer extends zio.App {
  private lazy val resourcePath = Paths.get(getClass.getClassLoader.getResource("site").toURI)

  override val platform: Platform = Platform.default.withTracing(Tracing.disabled)

  private val cacheHeaders =
    ("Cache-Control" -> "max-age=0, must-revalidate") :: Nil

  private def contentType(uri: String): String =
    if (uri.endsWith(".css"))
      "text/css"
    else
      Option(URLConnection.guessContentTypeFromName(uri)).getOrElse("application/octet-stream") match {
        case textType if textType startsWith "text/" => textType + "; charset=UTF-8"
        case typ => typ
      }

  private def handleErrors(fn: Request => ZIO[Blocking, Throwable, Response]): Request => ZIO[Blocking, HTTPError, Response] =
    fn andThen (_.catchAll {
      case err: HTTPError => ZIO.fail(err)
      case err => ZIO.fail(HTTPError.InternalServerError(err.getMessage))
    })


  private def uri(req: Request) = req.uri.toString match {
    case "/" | "" => "/index.html"
    case uri => uri
  }

  private def servePath(req: Request): ZIO[Blocking, Throwable, Response] =
    Response.fromPath(resourcePath.resolve(uri(req).stripPrefix("/")), req, contentType = contentType(uri(req)), headers = cacheHeaders)

  private def serveResource(req: Request): ZIO[Blocking, Throwable, Response] =
    Response.fromResource(s"site${uri(req)}", req, contentType = contentType(uri(req)), headers = cacheHeaders)

  private def requestHandler(args: List[String]): Request => ZIO[Blocking, HTTPError, Response] =
    if (args contains ("--from-resources"))
      handleErrors(serveResource)
    else
      handleErrors(servePath)

  private def responseCache(args: List[String]) =
    Response.permanentCache.handleAll(requestHandler(args)).build

  private def log(str: String): UIO[Unit] = ZIO.effectTotal(System.err.println(s"[APP]     $str"))

  private def formatHex(bytes: Array[Byte]) = bytes.map(b => String.format("%02x", Byte.box(b))).mkString

  private def handleWebsocketFrame(frame: Frame): UIO[Stream[Nothing, Take[Nothing, Frame]]] = frame match {
    case frame@Binary(data, _)       => log(s"Binary frame: 0x${formatHex(data)}") as Stream(Take.Value(frame))
    case frame@Text(data, _)         => log(s"Text frame: $data") as Stream(Take.Value(frame))
    case frame@Continuation(data, _) => log(s"Continuation frame: 0x${formatHex(data)}") as Stream(Take.Value(frame))
    case Ping => log("Ping!") as Stream(Take.Value(Pong))
    case Pong => log("Pong!") as Stream.empty
    case Close => log("Close") as Stream(Take.Value(Close), Take.End)
  }

  override def run(args: List[String]): ZIO[zio.ZEnv, Nothing, Int] =
    responseCache(args).use {
      cache =>
        Server.builder(new InetSocketAddress("127.0.0.1", 9121))
          .handleSome {
            case req @ Request.WebsocketRequest(_, uri, _, _, inputFrames) if uri.getPath startsWith "/ws" =>
              for {
                ws    <- Response.websocket(req, Stream.flatten(inputFrames.mapM(handleWebsocketFrame)).unTake)
              } yield ws
          }
        .handleSome(cache)
        .withMaxPending(Short.MaxValue)
        .withLogger(ServerLogger.Silent)
        .withLogger(ServerLogger.Debug)
        //.withConnectionIdleTimeout(Duration(5, TimeUnit.SECONDS))
        .serve.use {
          server => server.awaitShutdown.as(0)
        }.orDie
    }
}
