package uzhttp

package object server {
  val CRLF: Array[Byte] = Array('\r', '\n')
  val EmptyLine: Array[Byte] = CRLF ++ CRLF

  // The most copy-pasted StackOverflow snippet of all time, adapted to unprincipled Scala!
  private[server] def humanReadableByteCountSI(bytes: Long): String = {
    val s = if (bytes < 0) "-" else ""
    var b = if (bytes == Long.MinValue) Long.MaxValue else Math.abs(bytes)
    if (b < 1000L) return bytes + " B"
    if (b < 999950L) return "%s%.1f kB".format(s, b / 1e3)
    b /= 1000
    if (b < 999950L) return "%s%.1f MB".format(s, b / 1e3)
    b /= 1000
    if (b < 999950L) return "%s%.1f GB".format(s, b / 1e3)
    b /= 1000

    "%s%.1f TB".format(s, b / 1e3)
  }
}
