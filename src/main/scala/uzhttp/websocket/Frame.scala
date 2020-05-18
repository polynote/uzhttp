package uzhttp.websocket

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import zio.{Chunk, Ref, ZIO}
import zio.stream.{Sink, Stream, ZSink, ZStream, ZTransducer}
import ZStream.Take
import Frame.frameBytes

import scala.annotation.tailrec

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

  final case class FrameHeader(fin: Boolean, opcode: Byte, mask: Boolean, lengthIndicator: Byte)

  sealed trait State
  final case class NeedHeader(bytes: Chunk[Byte]) extends State
  final case class NeedShortLength(bytes: Chunk[Byte], header: FrameHeader) extends State
  final case class NeedLongLength(bytes: Chunk[Byte], header: FrameHeader) extends State
  final case class NeedMask(bytes: Chunk[Byte], header: FrameHeader, length: Int) extends State
  final case class ParsingFrame(bytes: Chunk[Byte], header: FrameHeader, length: Int, maskBytes: Int) extends State
  final case class Fail(err: FrameError) extends State
  final case class Emit(frame: Frame, remainder: Chunk[Byte]) extends State

  @tailrec
  def nextState(state: State, chunk: Chunk[Byte]): State = state match {
    case NeedHeader(prevBytes) =>
      val bytes = prevBytes ++ chunk
      if (bytes.size >= 2) {
        val b0 = bytes.head
        val b1 = bytes(1)
        val lengthIndicator = (b1 & 127).toByte
        val frameHeader = FrameHeader(b0 < 0, (b0 & 0xF).toByte, b1 < 0, (b1 & 127).toByte)
        val accum = bytes.drop(2)
        val next = lengthIndicator match {
          case 127 => NeedLongLength(accum, frameHeader)
          case 126 => NeedShortLength(accum, frameHeader)
          case n if frameHeader.mask => NeedMask(accum, frameHeader, n)
          case n => ParsingFrame(accum, frameHeader, java.lang.Byte.toUnsignedInt(n), 0)
        }
        nextState(next, Chunk.empty)
      } else NeedHeader(bytes)
    case NeedShortLength(prevBytes, header) =>
      val bytes = prevBytes ++ chunk
      if (bytes.size >= 2) {
        val b0 = bytes.head
        val b1 = bytes(1)
        val length = (java.lang.Byte.toUnsignedInt(b0) << 8) | java.lang.Byte.toUnsignedInt(b1)
        val accum = bytes.drop(2)
        val next = if (header.mask) {
          NeedMask(accum, header, length.toInt)
        } else {
          ParsingFrame(accum, header, length.toInt, 0)
        }
        nextState(next, Chunk.empty)
      } else NeedShortLength(bytes, header)
    case NeedLongLength(prevBytes, header) =>
      val bytes = prevBytes ++ chunk
      if (bytes.size >= 8) {
        val length = ByteBuffer.wrap(bytes.take(8).toArray).getLong()
        if (length > Int.MaxValue) {
          Fail(FrameTooLong(length))
        } else {
          val accum = bytes.drop(8)
          val next = if (header.mask) {
            NeedMask(accum, header, length.toInt)
          } else {
            ParsingFrame(accum, header, length.toInt, 0)
          }
          nextState(next, Chunk.empty)
        }
      } else NeedLongLength(bytes, header)
    case NeedMask(prevBytes, header, length) =>
      val bytes = prevBytes ++ chunk
      if (bytes.size >= 4) {
        val mask = ByteBuffer.wrap(bytes.take(4).toArray).getInt()
        val accum = bytes.drop(4)
        nextState(ParsingFrame(accum, header, length, mask), Chunk.empty)
      } else NeedMask(bytes, header, length)
    case ParsingFrame(prevBytes, header, length, maskKey) =>
      val bytes = prevBytes ++ chunk
      if (bytes.length >= length) {
        val body = bytes.take(length).toArray
        if (header.mask) {
          applyMask(body, maskKey)
        }
        val remainder = bytes.drop(length)
        Emit(Frame(header.fin, header.opcode, body), remainder)
      } else ParsingFrame(bytes, header, length, maskKey)
    case fail@Fail(_) => fail
    case Emit(_, remainder) =>
      nextState(NeedHeader(remainder), chunk)
  }

  val parseFrames: ZTransducer[Any, FrameError, Byte, Frame] = ZTransducer.apply[Any, FrameError, Byte, Frame] {
    Ref.makeManaged[State](NeedHeader(Chunk.empty)).map { stateRef =>
      {
        case None =>
          stateRef.getAndSet(NeedHeader(Chunk.empty)).map {
            case Emit(frame, _) => Chunk(frame)
            case _              => Chunk.empty
          }
        case Some(chunk) =>
          stateRef.updateAndGet(state => nextState(state, chunk)).flatMap {
            case Emit(frame, remainder) =>
              stateRef.set(NeedHeader(remainder)).as(Chunk(frame))
            case Fail(err)              =>
              ZIO.fail(err)
            case _                      =>
              ZIO.succeed(Chunk.empty)
          }
      }
    }
  }

  // mask the given bytes with the given key, mutating the input array
  private def applyMask(bytes: Array[Byte], maskKey: Int): Unit = {
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


  // Parses websocket frames from the bytestream using the parseFrame sink
  private[uzhttp] def parse(stream: Stream[Throwable, Byte]): Stream[Throwable, Frame] = stream.aggregate(parseFrames)

  sealed abstract class FrameError(msg: String) extends Throwable(msg)
  // We don't handle frames that are over 2GB, because Java can't handle their length.
  final case class FrameTooLong(length: Long) extends FrameError(s"Frame length $length exceeds Int.MaxValue")
  case object NotEnoughBytes extends FrameError("Not enough bytes remaining")

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
