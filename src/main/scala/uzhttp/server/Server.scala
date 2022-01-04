package uzhttp
package server

import java.io.{ByteArrayOutputStream, InputStream}
import java.net.{InetSocketAddress, SocketAddress, URI}
import java.nio.ByteBuffer
import java.nio.channels._
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

import uzhttp.HTTPError.{BadRequest, NotFound, RequestTimeout}

import zio._
import zio.stream._

class Server private (
    channel: ServerSocketChannel,
    requestHandler: Request => IO[HTTPError, Response],
    errorHandler: HTTPError => IO[Nothing, Response],
    config: Server.Config,
    closed: Promise[Throwable, Unit]
) {

  /** @return
    *   The bound address of the server. This is useful if the port was
    *   configured to `0` in order to use an OS-selected free port.
    */
  def localAddress: Task[SocketAddress] =
    awaitUp *> ZIO.attempt(channel.getLocalAddress)

  /** @return
    *   A task which will complete when the server's socket channel is open.
    */
  def awaitUp: Task[Unit] =
    ZIO.attempt(channel.isOpen).repeatUntil(identity).unit

  def uri: UIO[Option[URI]] = localAddress
    .map {
      case inet: InetSocketAddress =>
        Some(
          new URI("http", null, inet.getHostName, inet.getPort, "/", null, null)
        )
      case _ => None
    }
    .orElse(ZIO.none)

  /** Shut down the server.
    */
  def shutdown(): UIO[Unit] =
    ZIO.attempt(if (channel.isOpen) channel.close()).intoPromise(closed).unit

  def awaitShutdown: IO[Throwable, Unit] = closed.await

  private def serve(): RIO[Clock, Nothing] =
    Server.ChannelSelector(channel, requestHandler, errorHandler, config).use {
      selector =>
        uri
          .someOrFail(())
          .flatMap(uri => ZIO.logInfo(s"Server listening on $uri"))
          .ignore *> selector.run
    }

}

object Server {

  case class Config(
      maxPending: Int = 0,
      responseTimeout: Duration = Duration.Infinity,
      connectionIdleTimeout: Duration = Duration.Infinity,
      inputBufferSize: Int = 8192
  )

  /** Create a server [[Builder]] using the specified address.
    */
  def builder(address: InetSocketAddress): Builder[Any] = Builder(address)

  final case class Builder[-R: Tag: IsNotIntersection] private[Server] (
      address: InetSocketAddress,
      config: Config = Config(),
      requestHandler: PartialFunction[Request, ZIO[R, HTTPError, Response]] =
        PartialFunction.empty,
      errorHandler: HTTPError => ZIO[R, Nothing, Response] =
        defaultErrorFormatter
  ) {

    /** Set the address on which the server should listen, replacing the
      * currently set address. Specifying a port of `0` will cause the server to
      * bind on an operating-system assigned free port.
      */
    def withAddress(address: InetSocketAddress): Builder[R] =
      copy(address = address)

    /** Set the maximum number of pending connections. The default of `0`
      * specifies a platform-specific default value.
      * @see
      *   ServerSocketChannel#bind(SocketAddress, Int)
      */
    def withMaxPending(maxPending: Int): Builder[R] =
      copy(config = config.copy(maxPending = maxPending))

    /** Set the timeout before which the request handler must begin a response.
      * The default is no timeout.
      */
    def withResponseTimeout(responseTimeout: Duration): Builder[R] =
      copy(config = config.copy(responseTimeout = responseTimeout))

    /** Set the timeout for closing idle connections (if the server receives no
      * request within the given timeout). The default is no timeout, relying on
      * clients to behave well and close their own idle connections.
      */
    def withConnectionIdleTimeout(idleTimeout: Duration): Builder[R] =
      copy(config = config.copy(connectionIdleTimeout = idleTimeout))

    /** Provide a total function which will handle all requests not handled by a
      * previously given partial handler given to [[handleSome]].
      */
    def handleAll[R1 <: R: Tag: IsNotIntersection](
        handler: Request => ZIO[R1, HTTPError, Response]
    ): Builder[R1] =
      copy(requestHandler = requestHandler orElse { case x => handler(x) })

    /** Provide a partial function which will handle matched requests not
      * already handled by a previously given partial handler.
      */
    def handleSome[R1 <: R: Tag: IsNotIntersection](
        handler: PartialFunction[Request, ZIO[R1, HTTPError, Response]]
    ): Builder[R1] =
      copy(requestHandler = requestHandler orElse handler)

    /** Provide an error formatter which turns an [[HTTPError]] from a failed
      * request into a [[Response]] to be returned to the client. The default
      * error formatter returns a plaintext response indicating the error.
      */
    def errorResponse[R1 <: R: Tag: IsNotIntersection](
        errorHandler: HTTPError => URIO[R1, Response]
    ): Builder[R1] =
      copy(errorHandler = errorHandler)

    private def build: ZManaged[R, Throwable, Server] =
      mkSocket(address, config.maxPending)
        .flatMap { channel =>
          Promise.make[Throwable, Unit].toManaged.flatMap { closed =>
            ZIO
              .environment[R]
              .flatMap { env =>
                ZIO.succeed(
                  new Server(
                    channel,
                    (requestHandler orElse unhandled) andThen (_.provideEnvironment(
                      env
                    )),
                    errorHandler andThen (_.provideEnvironment(env)),
                    config,
                    closed
                  )
                )
              }
              .toManagedWith { server =>
                ZIO.logInfo("Shutting down server") *> server.shutdown()
              }
          }
        }

    /** Start the server and begin serving requests. The returned [[Server]]
      * reference can be used to wait for the server to be up, to retrieve the
      * bound address, and to shut it down if desired. After using the returned
      * ZManaged, the server will be shut down (you can use ZManaged.useForever
      * to keep the server running until termination of your app)
      */
    def serve: ZManaged[R with Clock, Throwable, Server] = {
      val res = ZManaged.environment[R with Clock].flatMap { env =>
        build
          .tap(_.serve().forkManaged)
      }

      res
    }

  }

  private[uzhttp] trait ConnectionWriter {
    def withWriteLock[R, E](
        fn: WritableByteChannel => ZIO[R, E, Unit]
    ): ZIO[R, E, Unit]
    private def writeInternal(
        channel: WritableByteChannel,
        bytes: ByteBuffer
    ): Task[Unit] =
      ZIO
        .attempt(channel.write(bytes))
        .as(bytes)
        .repeatWhile(_.hasRemaining)
        .unit
    def write(bytes: ByteBuffer): Task[Unit] =
      withWriteLock(channel => writeInternal(channel, bytes))
    def write(bytes: Array[Byte]): Task[Unit] = write(ByteBuffer.wrap(bytes))
    def writeByteBuffers(buffers: Stream[Throwable, ByteBuffer]): Task[Unit] =
      withWriteLock(channel =>
        buffers.foreach(buf => writeInternal(channel, buf))
      )
    def writeByteArrays(arrays: Stream[Throwable, Array[Byte]]): Task[Unit] =
      writeByteBuffers(arrays.map(ByteBuffer.wrap))
    def transferFrom(
        header: ByteBuffer,
        src: FileChannel
    ): IO[Throwable, Unit] = withWriteLock { channel =>
      writeInternal(channel, header) *> ZIO
        .attemptBlocking(src.size())
        .flatMap { size =>
          writeInternal(channel, header) *> ZIO.attemptBlocking {
            var pos = 0L
            while (pos < size)
              pos += src.transferTo(pos, size - pos, channel)
          }
        }
    }

    def pipeFrom(
        header: ByteBuffer,
        is: InputStream,
        bufSize: Int
    ): IO[Throwable, Unit] = withWriteLock { channel =>
      writeInternal(channel, header) *> ZIO.attemptBlocking {
        val buf = new Array[Byte](bufSize)
        val byteBuf = ByteBuffer.wrap(buf)
        var numRead = is.read(buf)
        while (numRead != -1) {
          byteBuf.limit(numRead)
          byteBuf.position(0)
          channel.write(byteBuf)
          numRead = is.read(buf)
        }
      }
    }

    def tap: UIO[ConnectionWriter.TappedWriter] =
      ZIO.succeed(new ConnectionWriter.TappedWriter(this))
  }

  private[uzhttp] object ConnectionWriter {
    private final class TappedChannel(
        val underlying: AtomicReference[WritableByteChannel] =
          new AtomicReference(null)
    ) extends WritableByteChannel {
      val output: ByteArrayOutputStream = new ByteArrayOutputStream()
      val outputChannel: WritableByteChannel = Channels.newChannel(output)

      override def write(src: ByteBuffer): Int = {
        val dup = src.duplicate()
        val written = underlying.get.write(src)
        dup.limit(src.position())
        outputChannel.write(dup)
        written
      }

      override def isOpen: Boolean = underlying.get.isOpen
      override def close(): Unit = underlying.get.close()
    }

    final class TappedWriter(underlying: ConnectionWriter)
        extends ConnectionWriter {
      private val tappedChannel = new TappedChannel()
      override def withWriteLock[R, E](
          fn: WritableByteChannel => ZIO[R, E, Unit]
      ): ZIO[R, E, Unit] = underlying.withWriteLock { underlyingChannel =>
        tappedChannel.underlying.set(underlyingChannel)
        fn(tappedChannel)
      }
      def finish: UIO[ByteBuffer] = ZIO
        .succeed(
          tappedChannel.outputChannel.close()
        )
        .as(ByteBuffer.wrap(tappedChannel.output.toByteArray))
    }
  }

  private[uzhttp] final class Connection private (
      inputBuffer: ByteBuffer,
      curReq: Ref[Either[(Int, List[String]), ContinuingRequest]],
      curRep: Ref[Option[Response]],
      requestHandler: Request => IO[HTTPError, Response],
      errorHandler: HTTPError => UIO[Response],
      config: Config,
      private[Server] val channel: ReadableByteChannel
        with WritableByteChannel
        with SelectableChannel,
      locks: Connection.Locks,
      shutdown: Promise[Throwable, Unit],
      idleTimeoutFiber: Ref[Fiber.Runtime[Nothing, Option[Unit]]]
  ) extends ConnectionWriter {

    import config._
    import locks._

    override def withWriteLock[R, E](
        fn: WritableByteChannel => ZIO[R, E, Unit]
    ): ZIO[R, E, Unit] = writeLock.withPermit(fn(channel))

    /** Take n bytes from the top of the input buffer, and shift the remaining
      * bytes (up to the buffer's position), if any, to the beginning of the
      * buffer. Afterward, the buffer's position will be after the end of the
      * remaining bytes.
      *
      * PERF: A low-hanging performance improvement here would be to not shift
      * and rewind the buffer until it reaches the end. That would add a bit
      * more complexity, but could really boost performance.
      */
    private def takeAndRewind(n: Int) = {
      val arr = new Array[Byte](n)
      val pos = inputBuffer.position()
      val remainderLength = pos - n
      inputBuffer.rewind()
      inputBuffer.get(arr)

      if (remainderLength > 0) {
        val rem = inputBuffer.slice()
        rem.limit(remainderLength)
        inputBuffer.rewind()
        inputBuffer.put(rem)
      } else {
        inputBuffer.rewind()
      }
      arr
    }

    private val timeoutRequest: Request => ZIO[Clock, HTTPError, Response] =
      responseTimeout match {
        case Duration.Infinity           => requestHandler
        case duration if duration.isZero => requestHandler
        case duration =>
          requestHandler andThen
            (_.timeoutFail(
              RequestTimeout(
                s"Request could not be handled within ${duration.render}"
              )
            )(duration))
      }

    private def handleRequest(req: Request) = requestLock.withPermit {
      timeoutRequest(req)
        .catchAll(errorHandler)
        .timed
        .tap { case (dur, rep) =>
          ZIO.logDebug {
            val size = rep.headers
              .get("Content-Length")
              .map(cl =>
                try cl.toLong
                catch { case _: Throwable => -1 }
              )
              .filterNot(_ < 0)
              .map(humanReadableByteCountSI)
              .getOrElse("(Unknown size)")
            s"${req.uri} ${rep.status} $size (${dur.render} to start)"
          }
        }
        .map { case (dur, rep) =>
          val shouldClose = req.version match {
            case Version.Http09 => true
            case Version.Http10 =>
              !req.headers.get("Connection").contains("keepalive")
            case Version.Http11 =>
              req.headers.get("Connection").contains("close")
          }
          if (!rep.closeAfter && shouldClose)
            dur -> (req, rep.addHeader("Connection", "close"))
          else dur -> (req, rep)
        }
        .flatMap { case (startDuration, (req, rep)) =>
          curRep.set(Some(rep)) *> rep
            .writeTo(this)
            .onTermination { cause =>
              ZIO.logError(
                s"Error writing response; closing connection [${cause}]"
              ) *> close()
            }
            .ensuring {
              close().when(rep.closeAfter)
            }
            .timed
            .flatMap { case (finishDuration, _) =>
              curReq.set(Left(0 -> Nil)) <*
                ZIO.logInfo(s"Handled Request <${req.method}> to uri <${req.uri}> in <${(startDuration + finishDuration).toMillis}>ms")
            }
        }
    }

    val doRead: RIO[Clock, Unit] =
      readLock.withPermit {
        def bytesReceived: ZIO[Clock, HTTPError, Unit] = if (
          inputBuffer.position() > 0
        ) {
          val numBytes = inputBuffer.position()

          def readNext(
              state: Either[(Int, List[String]), ContinuingRequest]
          ): ZIO[Clock, HTTPError, Unit] =
            state match {
              case Right(req) =>
                req.bytesRemaining.flatMap {
                  case bytesRemaining if bytesRemaining <= numBytes =>
                    val remainderLength = (numBytes - bytesRemaining).toInt
                    val takeLength = numBytes - remainderLength
                    val chunk = takeAndRewind(takeLength)
                    req.submitBytes(Chunk.fromArray(chunk)) *> curReq.set(
                      Left(0 -> Nil)
                    ) *> bytesReceived

                  case _ if !inputBuffer.hasRemaining || req.noBufferInput =>
                    // take a chunk of data iff the buffer is full
                    val chunk = takeAndRewind(numBytes)
                    req.submitBytes(Chunk.fromArray(chunk))

                  case _ =>
                    ZIO.unit // wait for more data
                }

              case Left((prevPos, headerChunks)) =>
                // search for \r\n\r\n in the buffer to mark end of headers
                var found = -1
                var takeLimit = -1
                var idx = math.max(0, prevPos - 4)
                val end = inputBuffer.position() - 3
                while (found < 0 && idx < end) {
                  if (inputBuffer.get(idx) == '\r') {
                    takeLimit = idx - 1
                    idx += 1
                    if (inputBuffer.get(idx) == '\n') {
                      idx += 1
                      if (inputBuffer.get(idx) == '\r') {
                        idx += 1
                        if (inputBuffer.get(idx) == '\n') {
                          found = idx
                        } else {
                          return Connection.mismatchCRLFError
                        }
                      } else {
                        idx += 1
                      }
                    } else {
                      return Connection.mismatchCRLFError
                    }
                  } else {
                    idx += 1
                    takeLimit = -1
                  }
                }
                if (found >= 0) {
                  // finished the headers – decide what kind of request it is and build the request
                  val chunk = takeAndRewind(found + 1)
                  val reqString = (new String(
                    chunk,
                    StandardCharsets.US_ASCII
                  ) :: headerChunks).reverse.mkString.trim()
                  val mkReq = IO
                    .fromEither(Request.NoBody.fromReqString(reqString))
                    .flatMap {
                      case Request.NoBody(method, uri, version, headers)
                          if headers.get("Upgrade").contains("websocket") =>
                        for {
                          request <- Request.WebsocketRequest(
                            method,
                            uri,
                            version,
                            headers
                          )
                          _ <- curReq.set(Right(request))
                          _ <- handleRequest(request).forkDaemon
                          _ <- stopIdleTimeout
                        } yield ()

                      case Request.NoBody(method, uri, version, headers)
                          if headers.contains("Content-Length") && headers(
                            "Content-Length"
                          ) != "0" =>
                        for {
                          contentLength <- IO(headers("Content-Length").toLong)
                            .orElseFail(
                              BadRequest("Couldn't parse Content-Length")
                            )
                          request <- Request.ReceivingBody.create(
                            method,
                            uri,
                            version,
                            headers,
                            contentLength
                          )
                          _ <- curReq.set(Right(request))
                          _ <- handleRequest(request).forkDaemon
                        } yield ()

                      case request =>
                        handleRequest(request).forkDaemon
                    }

                  mkReq *> bytesReceived
                } else if (!inputBuffer.hasRemaining) { // only take a chunk of headers when the buffer is full
                  if (takeLimit > 0) {
                    // can safely take this chunk of header data and rewind the buffer – only take up to a \r to avoid splitting across the empty line chars
                    val chunk = takeAndRewind(takeLimit)
                    val remainderLength = inputBuffer.position()
                    curReq.set(
                      Left(
                        remainderLength -> (new String(
                          chunk,
                          StandardCharsets.US_ASCII
                        ) :: headerChunks)
                      )
                    ) *> bytesReceived
                  } else if (takeLimit < 0) {
                    // can safely take the whole data and rewind the buffer
                    val chunk = takeAndRewind(numBytes)
                    curReq.set(
                      Left(
                        0 -> (new String(
                          chunk,
                          StandardCharsets.US_ASCII
                        ) :: headerChunks)
                      )
                    )
                  } else {
                    // This can only happen if the buffer is catastrophically small (like 2 bytes and only contains \r\n)
                    Connection.mismatchCRLFError
                  }
                } else ZIO.unit
            }
          curReq.get.flatMap(readNext)
        } else ZIO.yieldNow

        ZIO
          .attempt(channel.read(inputBuffer))
          .flatMap {
            case -1 => close()
            case _  => resetIdleTimeout &> bytesReceived
          }
          .catchAll {
            case err: ClosedChannelException =>
              ZIO.logError(
                s"Client closed connection unexpectedly [${err.getMessage()}]"
              ) *> close()
            case err =>
              ZIO.logError(
                s"Closing connection due to read error [${err.getMessage()}]"
              ) *> close()
          }
      }

    private def endCurrentRequest = curReq.get.flatMap {
      case Right(req) => req.channelClosed()
      case _          => ZIO.unit
    }

    private def closeResponse = curRep.get.flatMap {
      case Some(rep) => rep.close
      case None      => ZIO.unit
    }

    def close(): UIO[Unit] =
      shutdown.succeed(()).flatMap {
        case true =>
          ZIO.logDebug(s"Closing connection") *>
            endCurrentRequest *>
            stopIdleTimeout *>
            closeResponse *>
            withWriteLock(channel => ZIO.succeed(channel.close()))

        case false => ZIO.unit
      }

    val awaitShutdown: IO[Throwable, Unit] = shutdown.await

    val resetIdleTimeout: URIO[Clock, Unit] =
      config.connectionIdleTimeout match {
        case Duration.Infinity => ZIO.unit
        case duration =>
          val timeoutClose = ZIO.when(channel.isOpen) {
            (ZIO.logDebug(
              s"Closing connection $this due to idle timeout (${config.connectionIdleTimeout.render})"
            ) *>
              close()).delay(duration)
          }

          locks.timeoutLock.withPermit {
            for {
              nextFiber <- timeoutClose.forkDaemon
              prevFiber <- idleTimeoutFiber.getAndSet(nextFiber)
              _ <- prevFiber.interruptFork
            } yield ()
          }
      }

    def stopIdleTimeout: UIO[Unit] =
      idleTimeoutFiber.get.flatMap(_.interrupt).unit

  }

  private object Connection {
    case class Locks(
        readLock: Semaphore,
        writeLock: Semaphore,
        requestLock: Semaphore,
        timeoutLock: Semaphore
    )
    object Locks {
      def make: UIO[Locks] = (
        Semaphore.make(1) <*>
          Semaphore.make(1) <*>
          Semaphore.make(1) <*>
          Semaphore.make(1)
      ).map(t => Locks.apply(t._1, t._2, t._3, t._4))
    }

    def apply(
        channel: SocketChannel,
        requestHandler: Request => IO[HTTPError, Response],
        errorHandler: HTTPError => UIO[Response],
        config: Config
    ): ZManaged[Clock, Nothing, Connection] = {
      for {
        curReq <- Ref.make[Either[(Int, List[String]), ContinuingRequest]](
          Left(0 -> Nil)
        )
        curRep <- Ref.make[Option[Response]](None)
        locks <- Locks.make
        shutdown <- Promise.make[Throwable, Unit]
        idleTimeout <- ZIO.unit
          .map(u => Some(u))
          .fork
          .flatMap(f => Ref.make[Fiber.Runtime[Nothing, Option[Unit]]](f))
        connection = new Connection(
          ByteBuffer.allocate(config.inputBufferSize),
          curReq,
          curRep,
          requestHandler,
          errorHandler,
          config,
          channel,
          locks,
          shutdown,
          idleTimeout
        )
      } yield connection
    }.toManagedWith(_.close()).tapZIO { conn =>
      config.connectionIdleTimeout match {
        case Duration.Infinity => ZIO.unit
        case _                 => conn.resetIdleTimeout
      }
    }

    private val mismatchCRLFError: IO[BadRequest, Nothing] =
      ZIO.fail(BadRequest("Header contains \\r without \\n"))
  }

  private class ChannelSelector(
      selector: Selector,
      serverSocket: ServerSocketChannel,
      ConnectKey: SelectionKey,
      requestHandler: Request => IO[HTTPError, Response],
      errorHandler: HTTPError => UIO[Response],
      config: Config
  ) {

    private def register(
        connection: Connection
    ): ZManaged[Any, Throwable, SelectionKey] =
      ZIO
        .attempt(
          connection.channel
            .register(selector, SelectionKey.OP_READ, connection)
        )
        .toManagedWith(key => ZIO.succeed(key.cancel()))

    private def selectedKeys = ZIO.attempt {
      selector.synchronized {
        val k = selector.selectedKeys()
        val ks = k.toArray(Array.empty[SelectionKey])
        ks.foreach(k.remove)
        ks
      }
    }

    def select: URIO[Clock, Unit] =
      ZIO
        .attemptBlockingCancelable(selector.select(500))(
          ZIO.succeed(selector.wakeup()).unit
        )
        .flatMap {
          case 0 =>
            ZIO.unit
          case _ =>
            selectedKeys.flatMap { keys =>
              ZIO.foreachParDiscard(keys) {
                case ConnectKey =>
                  ZIO
                    .attempt(Option(serverSocket.accept()))
                    .tapError(err =>
                      ZIO.logError(
                        s"Error accepting connection; server socket is closed [${err.getMessage()}]"
                      ) *>
                        close()
                    )
                    .someOrFail(())
                    .flatMap { conn =>
                      conn.configureBlocking(false)
                      Connection(conn, requestHandler, errorHandler, config)
                        .tap(register(_).orDie)
                        .use(_.awaitShutdown)
                        .forkDaemon
                        .unit
                    }
                    .forever
                    .ignore
                case key =>
                  ZIO
                    .attempt(key.attachment().asInstanceOf[Server.Connection])
                    .flatMap { conn =>
                      conn.doRead.catchAll { err =>
                        ZIO.logError(
                          s"Error reading from connection ${err.getMessage()}"
                        ) <* conn.close().forkDaemon
                      }
                    }
              }
            }
        }
        .catchAll { err =>
          ZIO.logDebug(
            s"Error selecting channels: ${err}\n" + err.getStackTrace
              .mkString("\n\tat ")
          )
        }
        .onInterrupt {
          ZIO.logDebug("Selector interrupted")
        }

    def close(): UIO[Unit] =
      ZIO.logDebug("Stopping selector") *>
        ZIO
          .foreach(selector.keys().toIterable)(k => ZIO.attempt(k.cancel()))
          .orDie *>
        ZIO.attempt(selector.close()).orDie *>
        ZIO.attempt(serverSocket.close()).orDie

    def run: RIO[Clock, Nothing] =
      (select *> ZIO.yieldNow).forever.onInterrupt {
        ZIO.logDebug("Selector loop interrupted")
      }
  }

  private object ChannelSelector {
    def apply(
        serverChannel: ServerSocketChannel,
        requestHandler: Request => IO[HTTPError, Response],
        errorHandler: HTTPError => UIO[Response],
        config: Config
    ): ZManaged[Any, Throwable, ChannelSelector] =
      ZIO
        .attempt(Selector.open())
        .toManagedWith(s => ZIO.succeed(s.close()))
        .flatMap { selector =>
          serverChannel.configureBlocking(false)
          val connectKey =
            serverChannel.register(selector, SelectionKey.OP_ACCEPT)
          ZIO
            .succeed(
              new ChannelSelector(
                selector,
                serverChannel,
                connectKey,
                requestHandler,
                errorHandler,
                config
              )
            )
            .toManagedWith(_.close())
        }
  }

  private[uzhttp] val unhandled
      : PartialFunction[Request, ZIO[Any, HTTPError, Nothing]] = { case req =>
    ZIO.fail(NotFound(req.uri.toString))
  }

  private val defaultErrorFormatter: HTTPError => ZIO[Any, Nothing, Response] =
    err =>
      ZIO.succeed(
        Response.plain(
          s"${err.statusCode} ${err.statusText}\n${err.getMessage}",
          status = err
        )
      )

  private def mkSocket(
      address: InetSocketAddress,
      maxPending: Int
  ): ZManaged[Any, Throwable, ServerSocketChannel] = ZIO
    .attempt {
      val socket = ServerSocketChannel.open()
      socket.configureBlocking(false)
      socket
    }
    .toManagedWith { channel =>
      ZIO.attempt(channel.close()).orDie
    }
    .mapZIO { channel =>
      ZIO.attemptBlocking(channel.bind(address, maxPending))
    }

}
