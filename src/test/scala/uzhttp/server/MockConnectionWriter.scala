package uzhttp.server

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

import zio.ZIO.effect
import zio.blocking.Blocking
import zio.{Chunk, Ref, Task, ZIO, stream}

import TestRuntime.runtime.unsafeRun

case class MockConnectionWriter(writtenBytes: Ref[Chunk[Byte]] = unsafeRun(Ref.make(Chunk.empty))) extends Server.ConnectionWriter {
  override def write(bytes: Array[Byte]): Task[Unit] = writtenBytes.update(_ ++ Chunk.fromArray(bytes))
  override def writeByteBuffers(buffers: stream.Stream[Throwable, ByteBuffer]): Task[Unit] = buffers.foreach {
    buf => writtenBytes.update(_ ++ Chunk.fromByteBuffer(buf))
  }
  override def writeByteArrays(arrays: stream.Stream[Throwable, Array[Byte]]): Task[Unit] = arrays.foreach {
    arr => writtenBytes.update(_ ++ Chunk.fromArray(arr))
  }
  override def transferFrom(header: ByteBuffer, src: FileChannel): ZIO[Blocking, Throwable, Unit] = {
    val buf = ByteBuffer.allocate(1024)
    writtenBytes.update(_ ++ Chunk.fromByteBuffer(header)) *> effect {
      buf.rewind()
      val read = src.read(buf)
      if (read > 0) {
        val slice = buf.duplicate()
        slice.rewind()
        slice.limit(read)
        (read, Chunk.fromByteBuffer(slice))
      } else (read, Chunk.empty)
    }.doUntil(t => t._1 < 0 || t._2.nonEmpty).flatMap {
      case (read, chunk) => writtenBytes.update(_ ++ chunk).as(read)
    }.doUntil(_ < 0).unit
  }

  override def pipeFrom(header: ByteBuffer, is: InputStream, bufSize: Int): ZIO[Blocking, Throwable, Unit] = {
    val buf = new Array[Byte](1024)
    writtenBytes.update(_ ++ Chunk.fromByteBuffer(header)) *> effect {
      val read = is.read(buf)
      if (read > 0) {
        (read, Chunk.fromArray(java.util.Arrays.copyOf(buf, read)))
      } else (read, Chunk.empty)
    }.doUntil(t => t._1 < 0 || t._2.nonEmpty).flatMap {
      case (read, chunk) => writtenBytes.update(_ ++ chunk).as(read)
    }.doUntil(_ < 0).unit
  }
}