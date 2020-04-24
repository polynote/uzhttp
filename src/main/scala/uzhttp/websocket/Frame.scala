package uzhttp.websocket

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import zio.{Chunk, ZIO}
import zio.stream.{Sink, Stream, Take, ZSink, ZStream}
import Frame.frameBytes

sealed trait Frame {
  def toBytes: ByteBuffer
}

object Frame {

  def apply(fin: Boolean, opcode: Byte, body: Array[Byte]): Frame = opcode match {
    case 0 => Continuation(body, fin)
    case 1 => Text(new String(body, StandardCharsets.UTF_8), fin)
    case 2 => Binary(body, fin)
    case 8 => Close
    case 9 => Ping
    case 10 => Pong
    case _ => throw new IllegalArgumentException("Invalid frame opcode")
  }

  case class FrameHeader(fin: Boolean, opcode: Byte, mask: Boolean, lengthIndicator: Byte)

  private abstract class ArrayReaderSink[T](numBytes: Int) extends ZSink[Any, NotEnoughBytes.type, Chunk[Byte], Chunk[Byte], T] {
    override final type State = Chunk[Byte]
    override final def cont(chunk: Chunk[Byte]): Boolean = chunk.length < numBytes
    override final def initial: ZIO[Any, Nothing, Chunk[Byte]] = ZIO.succeed(Chunk.empty)
    override final def step(state: Chunk[Byte], a: Chunk[Byte]): ZIO[Any, Nothing, Chunk[Byte]] = ZIO.succeed(state ++ a)
    override final def extract(chunk: Chunk[Byte]): ZIO[Any, NotEnoughBytes.type, (T, Chunk[Chunk[Byte]])] =
      if (chunk.length >= numBytes)
        ZIO.succeed((parseArray(chunk.take(numBytes).toArray), Chunk(chunk.drop(numBytes))))
      else ZIO.fail(NotEnoughBytes)

    def parseArray(arr: Array[Byte]): T
  }

  private abstract class BufReaderSink[T](numBytes: Int) extends ArrayReaderSink[T](numBytes) {
    override final def parseArray(arr: Array[Byte]): T = parseBuffer(ByteBuffer.wrap(arr))
    def parseBuffer(buf: ByteBuffer): T
  }

  private object ParseFrameHeader extends BufReaderSink[FrameHeader](2) {
    override final def parseBuffer(buf: ByteBuffer): FrameHeader = {
      val b0 = buf.get()
      val b1 = buf.get()
      FrameHeader(b0 < 0, (b0 & 0xF).toByte, b1 < 0, (b1 & 127).toByte)
    }
  }

  private object ParseLongLength extends BufReaderSink[Long](8) {
    override final def parseBuffer(buf: ByteBuffer): Long = buf.getLong()
  }

  private object ParseShortLength extends BufReaderSink[Long](2) {
    override final def parseBuffer(buf: ByteBuffer): Long = java.lang.Short.toUnsignedInt(buf.getShort()).toLong
  }

  private object ParseMask extends BufReaderSink[Int](4) {
    override final def parseBuffer(buf: ByteBuffer): Int = buf.getInt()
  }

  private class ParseBody(length: Int) extends ArrayReaderSink[Array[Byte]](length) {
    override final def parseArray(arr: Array[Byte]): Array[Byte] = arr
  }

  // Parses one frame from a websocket byte stream
  private val parseFrame: Sink[Throwable, Chunk[Byte], Chunk[Byte], Frame] = ParseFrameHeader.flatMap {
    case FrameHeader(fin, opcode, mask, lengthIndicator) =>
      val parseLen = lengthIndicator match {
        case 127 => ParseLongLength
        case 126 => ParseShortLength
        case n   => ZSink.succeed[Chunk[Byte], Long](n.toLong)
      }

      parseLen.flatMap {
        case len if len > Int.MaxValue => ZSink.fail(FrameTooLong(len))
        case len =>
          val parseMask = if (mask) ParseMask else ZSink.succeed[Chunk[Byte], Int](0)
          parseMask.flatMap {
            maskKey => new ParseBody(len.toInt).map {
              bytes =>
                if (mask) {
                  val maskBytes = Array[Byte]((maskKey >> 24).toByte, ((maskKey >> 16) & 0xFF).toByte, ((maskKey >> 8) & 0xFF).toByte, (maskKey & 0xFF).toByte)
                  var i = 0
                  while (i < bytes.length - 4) {
                    bytes(i) = (bytes(i) ^ maskBytes(0)).toByte
                    bytes(i + 1) = (bytes(i + 1) ^ maskBytes(1)).toByte
                    bytes(i + 2) = (bytes(i + 2) ^ maskBytes(2)).toByte
                    bytes(i + 3) = (bytes(i + 3) ^ maskBytes(3)).toByte
                    i += 4
                  }

                  while (i < bytes.length) {
                    bytes(i) = (bytes(i) ^ maskBytes(i % 4)).toByte
                    i += 1
                  }
                }
                Frame(fin, opcode, bytes)
            }
          }
      }
  }

  // Parses websocket frames from the bytestream using the parseFrame sink
  private[uzhttp] def parse(stream: Stream[Throwable, Chunk[Byte]]): Stream[Throwable, Frame] = stream.aggregate(parseFrame).map(Take.Value(_)).catchAll {
    case NotEnoughBytes => ZStream(Take.End)
    case err => ZStream.fail(err)
  }.unTake

  // We don't handle frames that are over 2GB, because Java can't handle their length.
  final case class FrameTooLong(length: Long) extends Throwable(s"Frame length $length exceeds Int.MaxValue")
  case object NotEnoughBytes extends Throwable("Not enough bytes remaining")

  private[websocket] def frameSize(payloadLength: Int) =
    if (payloadLength < 126)
      2 + payloadLength
    else if (payloadLength <= 0xFFFF)
      4 + payloadLength
    else
      10 + payloadLength

  private[websocket] def writeLength(len: Int, buf: ByteBuffer) =
    if (len < 126) {
      buf.put(len.toByte)
    } else if (len <= 0xFFFF) {
      buf.put(126.toByte)
      buf.putShort(len.toShort)
    } else {
      buf.put(127.toByte)
      buf.putLong(len.toLong)
    }

  private[websocket] def frameBytes(op: Byte, payload: Array[Byte], fin: Boolean = true) = {
    val buf = ByteBuffer.allocate(frameSize(payload.length))
    buf.put(if (fin) (op | 128).toByte else op)
    writeLength(payload.length, buf)
    buf.put(payload)
    buf.rewind()
    buf
  }
}


final case class Continuation(data: Array[Byte], isLast: Boolean = true) extends Frame {
  override def toBytes: ByteBuffer = frameBytes(0, data, isLast)
}

final case class Text(data: String, isLast: Boolean = true) extends Frame {
  override def toBytes: ByteBuffer = frameBytes(1, data.getBytes(StandardCharsets.UTF_8), isLast)
}

final case class Binary(data: Array[Byte], isLast: Boolean = true) extends Frame {
  override def toBytes: ByteBuffer = frameBytes(2, data, isLast)
}

case object Close extends Frame {
  override val toBytes: ByteBuffer = frameBytes(8, Array.empty)
}

case object Ping extends Frame {
  override val toBytes: ByteBuffer = frameBytes(9, Array.empty)
}

case object Pong extends Frame {
  override val toBytes: ByteBuffer = frameBytes(10, Array.empty)
}
