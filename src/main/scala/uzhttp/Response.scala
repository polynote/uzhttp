package uzhttp

import java.io.InputStream
import java.net.URI
import java.nio.{ByteBuffer, MappedByteBuffer}
import java.nio.channels.FileChannel
import java.nio.charset.{Charset, StandardCharsets}
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{Files, Path, Paths, StandardOpenOption}
import java.security.MessageDigest
import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneOffset, ZonedDateTime}
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

import uzhttp.header.Headers
import Headers.{CacheControl, ContentLength, ContentType, IfModifiedSince, LastModified}
import uzhttp.server.Server
import uzhttp.HTTPError.{BadRequest, NotFound}
import uzhttp.Request.Method
import uzhttp.server.Server.ConnectionWriter
import uzhttp.websocket.Frame
import zio.ZIO.{effect, effectTotal}
import zio.blocking.{Blocking, effectBlocking}
import zio.stream.Stream
import zio.{Chunk, IO, Promise, RIO, Ref, UIO, URIO, ZIO, ZManaged}

trait Response {
  def headers: Headers
  def status: Status

  /**
    * Size of response body (excluding headers)
    */
  def size: Long

  def addHeaders(headers:(String, String)*): Response
  def addHeader(name: String, value: String): Response = addHeaders((name, value))
  def removeHeader(name: String): Response

  /**
    * Add cache-control header enabling modification time checking on the client
    */
  def withCacheControl: Response = addHeader(CacheControl, "max-age=0, must-revalidate")

  /**
    * Cache the response lazily in memory, for repeated use. Be careful when using this – keep in mind that this reponse
    * could have been tailored for a particular request and won't work for a different request (e.g. it could be a
    * 304 Not Modified response due to the request's If-Modified-Since header)
    *
    * @return A new Response which caches this response's body in memory.
    */
  def cached: UIO[Response] = Response.CachedResponse.make(this)
  def cachedManaged: ZManaged[Any, Nothing, Response] = Response.CachedResponse.managed(this)

  /**
    * Terminate the response, if it's still writing.
    */
  def close: UIO[Unit] = ZIO.unit

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

  lazy val notModified: Response = ConstResponse(Status.NotModified, Array.emptyByteArray, Nil)

  private def getModifiedTime(path: Path): RIO[Blocking, Instant] =
    effectBlocking(Files.getLastModifiedTime(path).toInstant)

  private def localPath(uri: URI): URIO[Blocking, Option[Path]] = uri match {
    case uri if uri.getScheme == "file" => effectBlocking(Paths.get(uri)).option
    case uri if uri.getScheme == "jar"  => effect(new URI(uri.getSchemeSpecificPart.takeWhile(_ != '!'))).flatMap(localPath).orElseSucceed(None)
    case _ => ZIO.none
  }

  private def parseModDate(rfc1123: String): IO[Option[Nothing], Instant] = effect(ZonedDateTime.parse(rfc1123, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant).orElseFail(None)
  private def parseModDateOpt(rfc1123: Option[String]): IO[Option[Nothing], Instant] =
    ZIO.fromOption(rfc1123).flatMap(str => effect(ZonedDateTime.parse(str, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant).orElseFail(None))

  private def checkModifiedSince(path: Path, ifModifiedSince: Option[String]): ZIO[Blocking, Option[Nothing], Response] = ZIO.fromOption {
    ifModifiedSince.map {
      dateStr =>
        parseModDate(dateStr).flatMap {
          ifModifiedSinceInstant =>
            getModifiedTime(path).orElseFail(None).flatMap {
              case mtime if mtime.isAfter(ifModifiedSinceInstant) => ZIO.fail(None)
              case _ => ZIO.succeed(notModified)
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
    * Note that you mustn't use this method if the response may be cached, as (depending on the request) it may produce
    * a `304 Not Modified` response. You don't want that being served to other clients! Use
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
    *         [[Response]]. If the resource isn't present, it will fail with [[HTTPError.NotFound]]. Since this response interacts
    *         with the filesystem, it can fail with other arbitrary Throwable failures; you'll probably need to
    *         catch these and convert them to [[HTTPError]] failures.
    */
  def fromPath(path: Path, request: Request, contentType: String = "application/octet-stream", status: Status = Status.Ok, headers: List[(String, String)] = Nil): ZIO[Blocking, Throwable, Response] =
    checkExists(path, request.uri.toString) *> checkModifiedSince(path, request.headers.get(IfModifiedSince)).orElse {
      for {
        size     <- effectBlocking(path.toFile.length())
        modified <- getModifiedTime(path).map(formatInstant).option
      } yield PathResponse(status, path, size, modified.map(LastModified -> _).toList ::: repHeaders(contentType, size, headers))
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
    *         [[Response]]. If the resource isn't present, it will fail with [[HTTPError.NotFound]]. Since this response interacts
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
    .someOrFail(NotFound(request.uri.toString))
    .flatMap {
      resource =>
        localPath(resource.toURI).get.tap(checkExists(_, request.uri.toString)).flatMap(path => checkModifiedSince(path, request.headers.get(IfModifiedSince))) orElse {
          resource match {
            case url if url.getProtocol == "file" =>
              for {
                path     <- effectBlocking(Paths.get(url.toURI))
                modified <- getModifiedTime(path).map(formatInstant)
                size     <- effectBlocking(Files.size(path))
              } yield PathResponse(status, path, size, (LastModified -> modified) :: repHeaders(contentType, size, headers))
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
                  headers = modified.map(LastModified -> _).toList ::: repHeaders(contentType, size, headers))
              } yield rep
          }
        }
    }

  def fromInputStream(
    stream: ZManaged[Blocking, Throwable, InputStream],
    size: Long,
    contentType: String = "application/octet-stream",
    status: Status = Status.Ok,
    headers: List[(String, String)] = Nil
  ): UIO[Response] = ZIO.succeed(InputStreamResponse(status, stream, size, repHeaders(contentType, size, headers)))

  def fromStream(stream: Stream[Nothing, Chunk[Byte]], size: Long, contentType: String = "application/octet-stream", status: Status = Status.Ok, ifModifiedSince: Option[String] = None, headers: List[(String, String)] = Nil): UIO[Response] =
    ZIO.succeed(ByteStreamResponse(status, size, stream.map(_.toArray), repHeaders(contentType, size, headers)))

  /**
    * Start a websocket request from a stream of [[uzhttp.websocket.Frame]]s.
    * @param req     The websocket request that initiated this response.
    * @param output  A stream of websocket [[uzhttp.websocket.Frame]]s to be sent to the client.
    * @param headers Any additional headers to include in the response.
    */
  def websocket(req: Request, output: Stream[Throwable, Frame], headers: List[(String, String)] = Nil): IO[BadRequest, WebsocketResponse] = {
    val handshakeHeaders = ZIO.effectTotal(req.headers.get("Sec-WebSocket-Key")).someOrFail(BadRequest("Missing Sec-WebSocket-Key")).map {
      acceptKey =>
        val acceptHash = Base64.getEncoder.encodeToString {
          MessageDigest.getInstance("SHA-1")
            .digest((acceptKey ++ "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII))
        }
        ("Upgrade", "websocket") :: ("Connection", "upgrade") :: ("Sec-WebSocket-Accept", acceptHash) :: headers
    }

    for {
      closed  <- Promise.make[Throwable, Unit]
      headers <- handshakeHeaders
    } yield WebsocketResponse(output, closed, headers)
  }

  private def repHeaders(contentType: String, contentLength: Long, headers: List[(String, String)]): List[(String, String)] =
    (ContentType -> contentType) :: (ContentLength -> contentLength.toString) :: headers

  private[uzhttp] def headerBytes(response: Response): Array[Byte] = {
    val statusLine = s"HTTP/1.1 ${response.status.statusCode} ${response.status.statusText}\r\n"
    val headers = response.headers.map {
      case (name, value) => s"$name: $value\r\n"
    }.mkString

    (statusLine + headers + "\r\n").getBytes(StandardCharsets.US_ASCII)
  }

  private final case class ByteStreamResponse private[uzhttp](
    status: Status,
    size: Long,
    body: Stream[Nothing, Array[Byte]],
    headers: Headers
  ) extends Response {
    override def addHeaders(headers: (String, String)*): ByteStreamResponse = copy(headers = this.headers ++ headers)
    override def removeHeader(name: String): Response = copy(headers = headers.removed(name))
    override private[uzhttp] def writeTo(connection: Server.ConnectionWriter): ZIO[Blocking, Throwable, Unit] =
      connection.writeByteArrays(body)
  }

  private final case class ConstResponse private[uzhttp] (
    status: Status,
    body: Array[Byte],
    headers: Headers
  ) extends Response {
    override val size: Long = body.length.toLong
    override def addHeaders(headers: (String, String)*): ConstResponse = copy(headers = this.headers ++ headers)
    override def removeHeader(name: String): Response = copy(headers = headers.removed(name))
    override private[uzhttp] def writeTo(connection: Server.ConnectionWriter): ZIO[Blocking, Throwable, Unit] =
      connection.writeByteArrays(Stream(Response.headerBytes(this), body))
  }

  final case class PathResponse private[uzhttp] (
    status: Status,
    path: Path,
    size: Long,
    headers: Headers
  ) extends Response {
    override def addHeaders(headers: (String, String)*): Response = copy(headers = this.headers ++ headers)
    override def removeHeader(name: String): Response = copy(headers = headers.removed(name))
    override private[uzhttp] def writeTo(connection: Server.ConnectionWriter): ZIO[Blocking, Throwable, Unit] = {
      effectBlocking(FileChannel.open(path, StandardOpenOption.READ)).toManaged(chan => effectTotal(chan.close())).use {
        chan =>
          connection.transferFrom(ByteBuffer.wrap(Response.headerBytes(this)), chan)
      }
    }

    /**
      * Produce a memory-mapped version of this response. NOTE: This will leak, because it can't be unmapped. Only
      * do this if you intend to keep the memory-mapped response for the duration of your app.
      */
    def mmap(uri: String): RIO[Blocking, Response] = for {
      _        <- checkExists(path, uri)
      modified <- getModifiedTime(path).map(formatInstant).option
      channel  <- effect(FileChannel.open(path, StandardOpenOption.READ))
      buffer   <- effect(channel.map(FileChannel.MapMode.READ_ONLY, 0, size))
    } yield MappedPathResponse(status, size, headers +? (LastModified, modified), buffer)

    /**
      * Like [[mmap]], but returns a managed resource that closes the file channel after use.
      */
    def mmapManaged(uri: String): ZManaged[Blocking, Throwable, Response] = {
      (checkExists(path, uri) *> getModifiedTime(path).map(formatInstant).option).toManaged_.flatMap {
        modified =>
          effect(FileChannel.open(path, StandardOpenOption.READ)).toManaged(c => effectTotal(c.close())).mapM {
            channel => effect(channel.map(FileChannel.MapMode.READ_ONLY, 0, size)).map {
              buffer => MappedPathResponse(status, size, headers +? (LastModified, modified), buffer)
            }
          }
      }
    }
  }

  private final case class MappedPathResponse private[uzhttp] (
    status: Status, size: Long, headers: Headers,
    mappedBuf: MappedByteBuffer
  ) extends Response {
    override def addHeaders(headers: (String, String)*): Response = copy(headers = this.headers ++ headers)
    override def removeHeader(name: String): Response = copy(headers = headers.removed(name))
    override private[uzhttp] def writeTo(connection: ConnectionWriter): ZIO[Blocking, Throwable, Unit] =
      connection.writeByteBuffers(Stream(ByteBuffer.wrap(headerBytes(this)), mappedBuf.duplicate()))
  }

  private final case class InputStreamResponse private[uzhttp](
    status: Status,
    getInputStream: ZManaged[Blocking, Throwable, InputStream],
    size: Long,
    headers: Headers
  ) extends Response {
    override def addHeaders(headers: (String, String)*): Response = copy(headers = this.headers ++ headers)
    override def removeHeader(name: String): Response = copy(headers = headers.removed(name))
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
    override val size: Long = -1L
    override val status: Status = Status.SwitchingProtocols
    override def addHeaders(headers: (String, String)*): Response = copy(headers = this.headers ++ headers)
    override def removeHeader(name: String): Response = copy(headers = headers.removed(name))
    override def close: UIO[Unit] = closed.succeed(()).unit
    override private[uzhttp] val closeAfter = true

    override private[uzhttp] def writeTo(connection: Server.ConnectionWriter): ZIO[Blocking, Throwable, Unit] = {
      connection.writeByteBuffers(Stream(ByteBuffer.wrap(Response.headerBytes(this))) ++ frames.map(_.toBytes).haltWhen(closed))
    }
  }

  /**
    * A response that passes through an underlying response the first time, while caching it in memory for any future
    * outputs.
    */
  private final class CachedResponse(
    underlying: Response,
    contents: Ref[Option[Promise[Throwable, ByteBuffer]]]
  ) extends Response {
    override def size: Long = underlying.size
    override def addHeaders(headers: (String, String)*): Response = new CachedResponse(underlying.addHeaders(headers: _*), contents)
    override def removeHeader(name: String): Response = new CachedResponse(underlying.removeHeader(name), contents)
    override def status: Status = underlying.status
    override def headers: Headers = underlying.headers
    override private[uzhttp] def writeTo(connection: ConnectionWriter): RIO[Blocking, Unit] = contents.get.flatMap {
      case Some(promise) => promise.await.flatMap(
        buf => connection.write(buf.duplicate())
      )
      case None => Promise.make[Throwable, ByteBuffer].flatMap {
        promise =>
          contents.updateSomeAndGet {
            case None => Some(promise)
          }.someOrFail(new IllegalStateException("Promise should exist")).flatMap {
            promise =>
              connection.tap.flatMap {
                tappedConnection =>
                  underlying.writeTo(tappedConnection).flatMap {
                    _ => tappedConnection.finish >>= promise.succeed
                  }
              }.tapError(promise.fail)
          }
      }.unit
    }

    def free: UIO[Unit] = contents.setAsync(None)
  }

  private object CachedResponse {
    def make(underlying: Response): UIO[CachedResponse] = Ref.make[Option[Promise[Throwable, ByteBuffer]]](None).map {
      promise => new CachedResponse(underlying, promise)
    }

    def managed(underlying: Response): ZManaged[Any, Nothing, CachedResponse] = make(underlying).toManaged(_.free)
  }

  /**
    * A cache that memoizes responses for eligible requests, and can cache response bodies of eligible responses
    */
  class PermanentCache(
    shouldMemoize: Request => Boolean,
    cachedResponse: (Request, Response) => ZIO[Any, Unit, Response],
    cacheKey: Request => String,
    requestHandler: PartialFunction[Request, IO[HTTPError, Response]]
  ) extends PartialFunction[Request, IO[HTTPError, Response]] {
    private val cache: ConcurrentHashMap[String, Promise[HTTPError, Response]] = new ConcurrentHashMap()

    override def isDefinedAt(request: Request): Boolean = requestHandler.isDefinedAt(request)
    override def apply(request: Request): IO[HTTPError, Response] = if (shouldMemoize(request)) {
      val key = cacheKey(request)
      cache.get(key) match {
        case null =>
          Promise.make[HTTPError, Response].flatMap {
            promise =>
              cache.putIfAbsent(key, promise)
              val p = cache.get(key)
              requestHandler(request.removeHeader(IfModifiedSince)).tapError(p.fail).flatMap {
                response => cachedResponse(request, response).orElseSucceed(response)
              }.tap(p.succeed)
          }
        case promise => promise.await.flatMap {
          response =>
              ZIO.mapN(parseModDateOpt(response.headers.get(LastModified)), parseModDateOpt(request.headers.get(IfModifiedSince)))(_ isBefore _)
                .filterOrFail(_ == false)(None)
                .as(notModified)
                .orElseSucceed(response)
          }
      }
    } else requestHandler(request)

  }

  object PermanentCache {
    def defaultCachedResponse(mmapThreshold: Int = 1 << 20): (Request, Response) => ZIO[Blocking, Unit, Response] =
      (req, rep) => rep match {
        case rep if rep.size >= 0 && rep.size < mmapThreshold => CachedResponse.make(rep)
        case rep: PathResponse => rep.mmap(req.uri.toString).orElseFail(())
        case _ => ZIO.fail(())
      }

    val alwaysCache: (Request, Response) => ZIO[Blocking, Unit, Response] =
      (req, rep) => rep.cached

    def defaultShouldMemoize: Request => Boolean = req => req.method == Method.GET && !req.headers.get("Upgrade").contains("websocket")

    case class Builder[R] private[uzhttp] (
      cachedResponse: (Request, Response) => ZIO[R, Unit, Response],
      requestHandler: PartialFunction[Request, ZIO[R, HTTPError, Response]] = PartialFunction.empty,
      shouldMemoize: Request => Boolean = defaultShouldMemoize,
      cacheKey: Request => String = _.uri.toString
    ) {
      /**
        * @see [[uzhttp.server.Server.Builder.handleSome]]
        */
      def handleSome[R1 <: R](handler: PartialFunction[Request, ZIO[R1, HTTPError, Response]]): Builder[R1] = copy(requestHandler = requestHandler orElse handler)

      /**
        * @see [[uzhttp.server.Server.Builder.handleAll]]
        */
      def handleAll[R1 <: R](handler: Request => ZIO[R1, HTTPError, Response]): Builder[R1] = copy(requestHandler = requestHandler orElse { case req => handler(req) })

      /**
        * Provide a test which decides whether or not to memoize the response for a given request. The default is
        * to memoize all GET requests (other than websocket requests) and not memoize any other requests.
        */
      def memoizeIf(test: Request => Boolean): Builder[R] = copy(shouldMemoize = test)

      /**
        * Provide a function which generates a cached response given a request and response. The default behavior is:
        * - If the response is smaller than ~1MB (`2^20` bytes) then cache it in memory
        * - If the response is larger than 1MB and is a [[PathResponse]], permanently memory-map it rather than storing it on heap (kernel manages the cache)
        * - Otherwise, just memoize the response (don't cache it)
        */
      def cacheWith[R1 <: R](fn: (Request, Response) => ZIO[R1, Unit, Response]): Builder[R1] = copy(cachedResponse = fn)

      /**
        * Provide a function which extracts a String cache key from a request. The default is to use the request's
        * entire URI as the cache key.
        */
      def withCacheKey(key: Request => String): Builder[R] = copy(cacheKey = key)

      /**
        * Return the configured cache as a ZManaged value
        */
      def build: ZManaged[R, Nothing, PermanentCache] = ZManaged.environment[R].map {
        env =>
          new PermanentCache(
            shouldMemoize,
            (req, rep) => cachedResponse(req, rep).provide(env),
            cacheKey,
            requestHandler.andThen(_.provide(env)))
      }
    }
  }

  /**
    * Build a caching layer which can memoize and cache responses in memory for the duration of the server's lifetime.
    */
  def permanentCache: PermanentCache.Builder[Blocking] = PermanentCache.Builder(cachedResponse = PermanentCache.defaultCachedResponse())

}


