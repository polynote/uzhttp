package uzhttp.server

import uzhttp.{Request, Response}
import zio.{UIO, ZIO}
import zio.ZIO.effectTotal
import zio.duration.Duration

case class ServerLogger[-R](
  info: (=> String) => ZIO[R, Nothing, Unit],
  request: (Request, Response, Duration, Duration) => ZIO[R, Nothing, Unit],
  error: (String, Throwable) => ZIO[R, Nothing, Unit],
  debugError: (String, Throwable) => ZIO[R, Nothing, Unit],
  debug: (=> String) => ZIO[R, Nothing, Unit] = ServerLogger.noLog
) {
  def provide(R: R): ServerLogger[Any] = copy(
    info = info andThen (_.provide(R)),
    request = (req, rep, sd, fd) => request(req, rep, sd, fd).provide(R),
    error = (msg, err) => error(msg, err).provide(R),
    debugError = (msg, err) => debugError(msg, err).provide(R),
    debug = debug andThen (_.provide(R))
  )
}

object ServerLogger {
  def defaultRequestLogger(req: Request, rep: Response, startDuration: Duration, finishDuration: Duration): UIO[Unit] = {
    val closedTag = if (rep.closeAfter) "(closed)" else "(keepalive)"
    val finishTime = finishDuration.render
    val totalTime = (startDuration + finishDuration).render
    effectTotal(System.err.println(s"[REQUEST] ${req.method.name} ${req.uri} ${rep.status} ($finishTime to finish, $totalTime total) $closedTag"))
  }

  val defaultInfoLogger: (=> String) => UIO[Unit] = str => effectTotal(System.err.println(s"[INFO]    $str"))
  val defaultErrorLogger: (String, Throwable) => UIO[Unit] = (str, err) => effectTotal { System.err.println(s"[ERROR]   $str"); err.printStackTrace(System.err) }
  val defaultDebugLogger: (=> String) => UIO[Unit] = str => effectTotal(System.err.println(s"[DEBUG]   $str"))
  val defaultDebugErrorLogger: (String, Throwable) => ZIO[Any, Nothing, Unit] = (str, err) => effectTotal { System.err.println(s"[DEBUG]   $str"); err.printStackTrace(System.err) }
  val noLog: (=> String) => UIO[Unit] = _ => ZIO.unit
  val noLogRequests: (Request, Response, Duration, Duration) => UIO[Unit] = (_, _, _, _) => ZIO.unit
  val noLogErrors: (String, Throwable) => ZIO[Any, Nothing, Unit] = (_, _) => ZIO.unit

  lazy val Default: ServerLogger[Any] = ServerLogger(defaultInfoLogger, defaultRequestLogger, defaultErrorLogger, noLogErrors, noLog)
  lazy val Debug: ServerLogger[Any] = Default.copy(debug = defaultDebugLogger, debugError = defaultDebugErrorLogger)
  lazy val Quiet: ServerLogger[Any] = Default.copy(info = noLog, request = noLogRequests)
  lazy val Silent: ServerLogger[Any] = Quiet.copy(error = noLogErrors)
}
