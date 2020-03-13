package uzhttp

import java.nio.channels.SelectionKey

package object server {
  val CRLF: Array[Byte] = Array('\r', '\n')
  val EmptyLine: Array[Byte] = CRLF ++ CRLF

  // The most copy-pasted StackOverflow snippet of all time, adapted to unprincipled Scala!
  private[server] def humanReadableByteCountSI(bytes: Long): String = {
    val s = if (bytes < 0) "-" else ""
    var b = if (bytes == Long.MinValue) Long.MaxValue else Math.abs(bytes)
    if (b < 1000L) return bytes.toString + " B"
    if (b < 999950L) return "%s%.1f kB".format(s, b / 1e3)
    b /= 1000
    if (b < 999950L) return "%s%.1f MB".format(s, b / 1e3)
    b /= 1000
    if (b < 999950L) return "%s%.1f GB".format(s, b / 1e3)
    b /= 1000

    "%s%.1f TB".format(s, b / 1e3)
  }

  private[server] implicit class IterateKeys(val self: java.util.Set[SelectionKey]) extends AnyVal {
    def toIterable: Iterable[SelectionKey] = new Iterable[SelectionKey] {
      override def iterator: Iterator[SelectionKey] = {
        val jIterator = self.iterator()
        new Iterator[SelectionKey] {
          override def hasNext: Boolean = jIterator.hasNext
          override def next(): SelectionKey = jIterator.next()
        }
      }
    }
  }

  private[server] implicit class EitherCompat[+L, +R](val self: Either[L, R]) extends AnyVal {
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
