import java.net.URI

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

}
