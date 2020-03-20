import java.io.FileNotFoundException
import java.net.URI
import java.nio.file.NoSuchFileException

import zio.ZIO

package object uzhttp {


  private[uzhttp] val CRLF: Array[Byte] = Array('\r', '\n')

  // To provide right-bias in Scala 2.11
  private[uzhttp] implicit class EitherCompat[+L, +R](val self: Either[L, R]) extends AnyVal {
    def flatMap[L1 >: L, R1](fn: R => Either[L1, R1]): Either[L1, R1] = self match {
      case Left(l)  => Left(l)
      case Right(r) => fn(r)
    }

    def map[R1](fn: R => R1): Either[L, R1] = self match {
      case Left(l) => Left(l)
      case Right(r) => Right(fn(r))
    }
  }

  implicit class RefineOps[R](val self: ZIO[R, Throwable, Response]) extends AnyVal {
    /**
      * A default mapping of arbitrary Throwable to HTTP error. If it's a FileNotFoundException or NoSuchFileException,
      * a [[uzhttp.HTTPError.NotFound]] is generated; otherwise the error is wrapped in [[uzhttp.HTTPError.InternalServerError]].
      *
      * This is provided for convenience, in case you don't want to handle non-HTTP errors yourself.
      */
    def refineHTTP(req: Request): ZIO[R, HTTPError, Response] = self.mapError {
      case err: HTTPError => err
      case err: FileNotFoundException => HTTPError.NotFound(req.uri.toString)
      case err: NoSuchFileException => HTTPError.NotFound(req.uri.toString)
      case err => HTTPError.InternalServerError(err.getMessage, Some(err))
    }
  }

}
