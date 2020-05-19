package uzhttp.websocket

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import zio.{Chunk, Ref, ZIO, ZManaged}
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

  /**
    * Parsing using mutable state. That's not good, but it is several times faster than the immutable state version.
    */
  private object FastParsing {
    val NeedHeader      = 0
    val NeedShortLength = 1
    val NeedLongLength  = 2
    val NeedMask        = 3
    val ReceivingBytes  = 4
    val LengthTooLong   = 5

    // this is mutable in order to avoid a lot of allocation during parsing
    final class State(
     var parsingState: Int = NeedHeader,
     var header: FrameHeader = null,
     var length: Int = -1,
     var maskKey: Int = 0,
     var remainder: Chunk[Byte] = Chunk.empty,
     var parsedFrames: Chunk[Frame] = Chunk.empty
    ) {
      val bufArray: Array[Byte] = new Array[Byte](10)
      val buf: ByteBuffer = ByteBuffer.wrap(bufArray)
      def reset(): Unit = {
        this.parsingState = NeedHeader
        this.header = null
        this.length = -1
        this.maskKey = 0
        ()
      }

      def emit(): Chunk[Frame] = {
        val result = this.parsedFrames
        this.parsedFrames = Chunk.empty
        result
      }

      def emitAndReset(): Chunk[Frame] = {
        this.reset()
        this.remainder = Chunk.empty
        this.emit()
      }
    }

    @tailrec def updateState(state: State): Unit = {
      val bytes = state.remainder
      state.parsingState match {
        case NeedHeader if bytes.size >= 2 =>
          val b0 = bytes.head
          val b1 = bytes(1)
          val lengthIndicator = (b1 & 127).toByte
          val mask = b1 < 0
          state.header = FrameHeader(b0 < 0, (b0 & 0xF).toByte, mask, lengthIndicator)
          state.parsingState = lengthIndicator match {
            case 127 => NeedLongLength
            case 126 => NeedShortLength
            case n if mask =>
              state.length = n
              state.remainder = state.remainder.drop(2)
              NeedMask
            case n =>
              state.length = n
              ReceivingBytes
          }
          updateState(state)
        case NeedShortLength if bytes.size >= 4 =>
          state.bufArray(0) = bytes(2)
          state.bufArray(1) = bytes(3)
          state.length = java.lang.Short.toUnsignedInt(state.buf.getShort(0))
          state.remainder = state.remainder.drop(4)
          state.parsingState = if (state.header.mask) NeedMask else ReceivingBytes
          updateState(state)
        case NeedLongLength if bytes.size >= 10 =>
          bytes.copyToArray(state.bufArray, 0, 10)
          val length = state.buf.getLong(2)
          if (length > Int.MaxValue) {
            state.parsingState = LengthTooLong
          } else {
            state.length = length.toInt
            state.remainder = state.remainder.drop(10)
            state.parsingState = if (state.header.mask) NeedMask else ReceivingBytes
            updateState(state)
          }
        case NeedMask if bytes.size >= 4 =>
          bytes.copyToArray(state.bufArray, 0, 4)
          state.maskKey = state.buf.getInt(0)
          state.remainder = state.remainder.drop(4)
          state.parsingState = ReceivingBytes
          updateState(state)
        case ReceivingBytes if bytes.size >= state.length =>
          val body = bytes.take(state.length).toArray
          if (state.header.mask && state.maskKey != 0) {
            applyMask(body, state.maskKey)
          }
          state.remainder = state.remainder.drop(state.length)
          state.parsedFrames = state.parsedFrames + Frame(state.header.fin, state.header.opcode, body)
          state.reset()
          updateState(state)
        case _ =>
      }
    }

    val parseFrames: ZTransducer[Any, FrameError, Byte, Frame] = ZTransducer.apply[Any, FrameError, Byte, Frame] {
      ZManaged.succeed(new State()).map {
        state =>
          {
            case None =>
              updateState(state)
              if (state.parsingState == LengthTooLong)
                ZIO.fail(FrameTooLong(state.buf.getLong(2)))
              else
                ZIO.succeed(state.emitAndReset())
            case Some(chunk) =>
              state.remainder = state.remainder ++ chunk
              updateState(state)
              if (state.parsingState == LengthTooLong)
                ZIO.fail(FrameTooLong(state.buf.getLong(2)))
              else
                ZIO.succeed(state.emit())
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

  // Parses websocket frames from the bytestream using the parseFrame transducer
  private[uzhttp] def parse(stream: Stream[Throwable, Byte]): Stream[Throwable, Frame] = stream.aggregate(FastParsing.parseFrames)

  sealed abstract class FrameError(msg: String) extends Throwable(msg)
  // We don't handle frames that are over 2GB, because Java can't handle their length.
  final case class FrameTooLong(length: Long) extends FrameError(s"Frame length $length exceeds Int.MaxValue")

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
