package uzhttp.server

import java.nio.ByteBuffer
import java.nio.channels.WritableByteChannel

import zio.{Chunk, RIO, Ref, Semaphore, ZIO}
import TestRuntime.runtime.unsafeRun

case class MockConnectionWriter(writtenBytes: Ref[Chunk[Byte]] = unsafeRun(Ref.make(Chunk.empty))) extends Server.ConnectionWriter {
  private val writeLock: Semaphore = unsafeRun(Semaphore.make(1L))
  private val channel = new WritableByteChannel {
    override def write(src: ByteBuffer): Int = if (src.hasRemaining) {
      val chunk = Chunk.fromByteBuffer(src)
      src.position(src.position() + chunk.size)
      unsafeRun(writtenBytes.update(_ ++ chunk).as(chunk.size))
    } else 0
    override def isOpen: Boolean = true
    override def close(): Unit = ()
  }

  override def withWriteLock[R, E](fn: WritableByteChannel => ZIO[R, E, Unit]): ZIO[R, E, Unit] = writeLock.withPermit(fn(channel))
}