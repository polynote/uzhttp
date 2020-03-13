package uzhttp.server


import uzhttp.header.Headers
import uzhttp.server.Websocket.Frame
import zio.stream.{Stream, StreamChunk, Take, ZStream}
import zio.{Chunk, IO, Queue, Ref, UIO, ZIO}


trait Request {
  def method: Request.Method
  def uri: String
  def version: Request.Version
  def headers: Map[String, String]
  def body: Option[StreamChunk[HTTPError, Byte]]

  def addHeader(name: String, value: String): Request
  def addHeaders(headers: (String, String)*): Request = headers.foldLeft(this) {
    case (r, (k, v)) => r.addHeader(k, v)
  }
}

trait ContinuingRequest extends Request {
  def submitBytes(chunk: Chunk[Byte]): UIO[Unit]
  def bytesRemaining: UIO[Long]
}

object Request {

  sealed abstract class Version(val string: String)
  case object Http09 extends Version("0.9")
  case object Http10 extends Version("1.0")
  case object Http11 extends Version("1.1")
  object Version {
    def parse(str: String): Version = str.slice(5, 8) match {
      case "0.9" => Http09
      case "1.0" => Http10
      case "1.1" => Http11
      case _ =>
        throw BadRequest("Invalid HTTP version identifier")
    }

    def parseEither(str: String): Either[BadRequest, Version] = str.slice(5, 8) match {
      case "0.9" => Right(Http09)
      case "1.0" => Right(Http10)
      case "1.1" => Right(Http11)
      case _     => Left(BadRequest("Invalid HTTP version identifier"))
    }
  }

  sealed abstract class Method(val name: String)
  case object GET extends Method("GET")
  case object HEAD extends Method("HEAD")
  case object POST extends Method("POST")
  case object PUT extends Method("PUT")
  case object DELETE extends Method("DELETE")
  case object TRACE extends Method("TRACE")
  case object OPTIONS extends Method("OPTIONS")
  case object CONNECT extends Method("CONNECT")
  case object PATCH extends Method("PATCH")

  object Method {
    val Methods: List[Method] = List(GET, HEAD, POST, PUT, DELETE, TRACE, OPTIONS, CONNECT, PATCH)
    def parse(str: String): Method = {
      val strU = str.toUpperCase
      Methods.find(_.name == strU).getOrElse(throw BadRequest("Invalid method"))
    }

    def parseEither(str: String): Either[BadRequest, Method] = {
      val strU = str.toUpperCase
      Methods.find(_.name == strU).map(Right(_)).getOrElse(Left(BadRequest("Invalid method")))
    }
  }

  private[server] final case class ReceivingBody(method: Method, uri: String, version: Version, headers: Headers, bodyQueue: Queue[Take[HTTPError, Chunk[Byte]]], received: Ref[Long], contentLength: Long) extends ContinuingRequest {
    override val body: Option[StreamChunk[HTTPError, Byte]] = Some(StreamChunk(Stream.fromQueueWithShutdown(bodyQueue).unTake))

    override def addHeader(name: String, value: String): Request = copy(headers = headers + (name -> value))
    override def bytesRemaining: UIO[Long] = received.get.map(contentLength - _)
    override def submitBytes(chunk: Chunk[Byte]): UIO[Unit] = bodyQueue.offer(Take.Value(chunk)) *> received.updateAndGet(_ + chunk.length).flatMap {
      case received if received >= contentLength => bodyQueue.offer(Take.End).unit
      case _ => ZIO.unit
    }
  }

  private[server] object ReceivingBody {
    private[server] def create(method: Method, uri: String, version: Version, headers: Headers, contentLength: Long): UIO[ReceivingBody] =
      ZIO.mapN(Queue.unbounded[Take[HTTPError, Chunk[Byte]]], Ref.make[Long](0L)) {
        (body, received) => new ReceivingBody(method, uri, version, headers, body, received, contentLength)
      }
  }

  private final case class ConstBody(method: Method, uri: String, version: Version, headers: Headers, bodyChunk: Chunk[Byte]) extends Request {
    override def body: Option[StreamChunk[Nothing, Byte]] = Some(StreamChunk(Stream(bodyChunk)))
    override def addHeader(name: String, value: String): Request = copy(headers = headers + (name -> value))
  }

  private[server] final case class NoBody(method: Method, uri: String, version: Version, headers: Headers) extends Request {
    override val body: Option[StreamChunk[Nothing, Byte]] = None
    override def addHeader(name: String, value: String): Request = copy(headers = headers + (name -> value))
  }

  private[server] object NoBody {
    def fromReqString(str: String): Either[BadRequest, NoBody] = str.linesWithSeparators.map(_.stripLineEnd).dropWhile(_.isEmpty).toList match {
      case Nil => Left(BadRequest("Empty request"))
      case first :: rest =>
        first.split(' ').toList match {
          case List(methodStr, uri, versionStr) => for {
            method  <- Method.parseEither(methodStr)
            version <- Version.parseEither(versionStr)
          } yield NoBody(method, uri, version, Headers.fromLines(rest))
        }
    }
  }

  def empty(method: Method = GET, version: Version = Http11, uri: String = "/"): Request = NoBody(method, uri, version, Headers("Connection" -> "close"))

  final class WebsocketRequest(
    val method: Method,
    val uri: String,
    val version: Version,
    val headers: Headers,
    chunks: Queue[Take[Nothing, Chunk[Byte]]]
  ) extends ContinuingRequest {
    override def addHeader(name: String, value: String): Request = new WebsocketRequest(method, uri, version, headers + (name -> value), chunks)
    override val body: Option[StreamChunk[HTTPError, Byte]] = None
    override val bytesRemaining: UIO[Long] = ZIO.succeed(Long.MaxValue)
    override def submitBytes(chunk: Chunk[Byte]): UIO[Unit] = chunks.offer(Take.Value(chunk)).unit
    lazy val frames: Stream[Throwable, Frame] = Frame.parse(ZStream.fromQueueWithShutdown(chunks).unTake)
  }

  object WebsocketRequest {
    def apply(method: Method, uri: String, version: Version, headers: Headers): UIO[WebsocketRequest] =
      Queue.unbounded[Take[Nothing, Chunk[Byte]]].map {
        chunks => new WebsocketRequest(method, uri, version, headers, chunks)
      }

    def unapply(req: Request): Option[(Method, String, Version, Headers, Stream[Throwable, Frame])] = req match {
      case req: WebsocketRequest => Some((req.method, req.uri, req.version, req.headers, req.frames))
      case _ => None
    }
  }

}