package uzhttp.server

import java.net.{InetSocketAddress, URLConnection}
import java.nio.file.Paths

import uzhttp.{HTTPError, Request, Response}
import zio.ZIO
import zio.blocking.Blocking
import zio.internal.{Platform, Tracing}

object TestServer extends zio.App {
  private lazy val resourcePath = Paths.get(getClass.getClassLoader.getResource("site").toURI)

  override val platform: Platform = Platform.default.withTracing(Tracing.disabled)

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

  private val indexPage = unsafeRun {
    Response.mmap(
      resourcePath.resolve("index.html"),
      "/index.html", "text/html; charset=UTF-8")
  }

  private def uri(req: Request) = req.uri.toString match {
    case "/" | "" => "/index.html"
    case uri => uri
  }

  private def servePath(req: Request): ZIO[Blocking, Throwable, Response] = req.uri.getPath match {
    case "/" | "" | "/index.html" => indexPage(req)
    case _ => Response.fromPath(resourcePath.resolve(uri(req).stripPrefix("/")), req, contentType = contentType(uri(req)))
  }

  private def serveResource(req: Request): ZIO[Blocking, Throwable, Response] =
    Response.fromResource(s"site${uri(req)}", req, contentType = contentType(uri(req)))


  override def run(args: List[String]): ZIO[zio.ZEnv, Nothing, Int] = Server.builder(new InetSocketAddress("127.0.0.1", 9121))
    .handleAll(
      if (args contains ("--from-resources"))
        handleErrors(serveResource)
      else
        handleErrors(servePath)
    )
    .withMaxPending(Short.MaxValue)
    .withLogger(ServerLogger.Silent)
    .serve.use {
      server => server.awaitShutdown.as(0)
    }.orDie
}
