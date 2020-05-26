package uzhttp

import java.net.URI

import uzhttp.header.Headers
import uzhttp.websocket.Frame
import uzhttp.HTTPError.BadRequest
import zio.stream.{Stream, Take, ZStream}
import zio.{Chunk, Queue, Ref, UIO, ZIO}


trait Request {
  def method: Request.Method
  def uri: URI
  def version: Version
  def headers: Map[String, String]
  def body: Option[Stream[HTTPError, Byte]]
  def addHeader(name: String, value: String): Request
  def addHeaders(headers: (String, String)*): Request = headers.foldLeft(this) {
    case (r, (k, v)) => r.addHeader(k, v)
  }
  def removeHeader(name: String): Request
}

trait ContinuingRequest extends Request {
  def submitBytes(chunk: Chunk[Byte]): UIO[Unit]
  def channelClosed(): UIO[Unit]
  def bytesRemaining: UIO[Long]
  def noBufferInput: Boolean
}

object Request {


  sealed abstract class Method(val name: String)


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

    case object GET extends Method("GET")
    case object HEAD extends Method("HEAD")
    case object POST extends Method("POST")
    case object PUT extends Method("PUT")
    case object DELETE extends Method("DELETE")
    case object TRACE extends Method("TRACE")
    case object OPTIONS extends Method("OPTIONS")
    case object CONNECT extends Method("CONNECT")
    case object PATCH extends Method("PATCH")
  }

  private[uzhttp] final case class ReceivingBody(method: Method, uri: URI, version: Version, headers: Headers, bodyQueue: Queue[Take[HTTPError, Byte]], received: Ref[Long], contentLength: Long) extends ContinuingRequest {
    override val body: Option[Stream[HTTPError, Byte]] = Some(Stream.fromQueue(bodyQueue).flattenTake)
    override val noBufferInput: Boolean = false
    override def addHeader(name: String, value: String): Request = copy(headers = headers + (name -> value))
    override def removeHeader(name: String): Request = copy(headers = headers.removed(name))
    override def bytesRemaining: UIO[Long] = received.get.map(contentLength - _)
    override def channelClosed(): UIO[Unit] = bodyQueue.offer(Take.end).unit
    override def submitBytes(chunk: Chunk[Byte]): UIO[Unit] = bodyQueue.offer(Take.chunk(chunk)) *> received.updateAndGet(_ + chunk.length).flatMap {
      case received if received >= contentLength => bodyQueue.offer(Take.end).unit
      case _ => ZIO.unit
    }
  }

  private[uzhttp] object ReceivingBody {
    private[uzhttp] def create(method: Method, uri: URI, version: Version, headers: Headers, contentLength: Long): UIO[ReceivingBody] =
      ZIO.mapN(Queue.unbounded[Take[HTTPError, Byte]], Ref.make[Long](0L)) {
        (body, received) => new ReceivingBody(method, uri, version, headers, body, received, contentLength)
      }
  }

  private final case class ConstBody(method: Method, uri: URI, version: Version, headers: Headers, bodyChunk: Chunk[Byte]) extends Request {
    override def body: Option[Stream[Nothing, Byte]] = Some(Stream.fromChunk(bodyChunk))
    override def addHeader(name: String, value: String): Request = copy(headers = headers + (name -> value))
    override def removeHeader(name: String): Request = copy(headers = headers.removed(name))
  }

  private[uzhttp] final case class NoBody(method: Method, uri: URI, version: Version, headers: Headers) extends Request {
    override val body: Option[Stream[Nothing, Byte]] = None
    override def addHeader(name: String, value: String): Request = copy(headers = headers + (name -> value))
    override def removeHeader(name: String): Request = copy(headers = headers.removed(name))
  }

  private[uzhttp] object NoBody {
    def fromReqString(str: String): Either[BadRequest, NoBody] = str.linesWithSeparators.map(_.stripLineEnd).dropWhile(_.isEmpty).toList match {
      case Nil => Left(BadRequest("Empty request"))
      case first :: rest =>
        first.split(' ').toList match {
          case List(methodStr, uri, versionStr) => for {
            uri     <- try Right(new URI(uri)) catch { case _: Throwable => Left(BadRequest("Malformed request URI")) }
            method  <- Method.parseEither(methodStr)
            version <- Version.parseEither(versionStr)
          } yield NoBody(method, uri, version, Headers.fromLines(rest))
        }
    }
  }

  // Produce an empty GET request on "/" with "Connection: close". Mainly for testing.
  def empty(method: Method = Method.GET, version: Version = Version.Http11, uri: String = "/"): Request =
    NoBody(method, new URI(uri), version, Headers("Connection" -> "close"))

  final class WebsocketRequest(
    val method: Method,
    val uri: URI,
    val version: Version,
    val headers: Headers,
    chunks: Queue[Take[Nothing, Byte]]
  ) extends ContinuingRequest {
    override def addHeader(name: String, value: String): Request = new WebsocketRequest(method, uri, version, headers + (name -> value), chunks)
    override def removeHeader(name: String): Request = new WebsocketRequest(method, uri, version, headers = headers.removed(name), chunks)
    override val body: Option[Stream[HTTPError, Byte]] = None
    override val bytesRemaining: UIO[Long] = ZIO.succeed(Long.MaxValue)
    override def submitBytes(chunk: Chunk[Byte]): UIO[Unit] = chunks.offer(Take.chunk(chunk)).unit
    override def channelClosed(): UIO[Unit] = chunks.offer(Take.end).unit
    override val noBufferInput: Boolean = true
    lazy val frames: Stream[Throwable, Frame] = Frame.parse(ZStream.fromQueue(chunks).flattenTake)
  }

  object WebsocketRequest {
    def apply(method: Method, uri: URI, version: Version, headers: Headers): UIO[WebsocketRequest] =
      Queue.unbounded[Take[Nothing, Byte]].map {
        chunks => new WebsocketRequest(method, uri, version, headers, chunks)
      }

    def unapply(req: Request): Option[(Method, URI, Version, Headers, Stream[Throwable, Frame])] = req match {
      case req: WebsocketRequest => Some((req.method, req.uri, req.version, req.headers, req.frames))
      case _ => None
    }
  }

}