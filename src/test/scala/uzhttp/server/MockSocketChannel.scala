package uzhttp.server

import java.nio.ByteBuffer
import java.nio.channels.spi.SelectorProvider
import java.nio.channels.{ReadableByteChannel, SelectableChannel, SelectionKey, Selector, WritableByteChannel}

import scala.util.Random

class MockSocketChannel(
  input: ByteBuffer,
  private var outputs: List[Array[Byte]],
  maxInputBytes: Int => Int = _ => Random.nextInt(128),
  maxOutputBytes: Int => Int = _ => Random.nextInt(128)
) extends SelectableChannel with ReadableByteChannel with WritableByteChannel {
  override def read(dst: ByteBuffer): Int = synchronized {
    val start = dst.position()
    val amountTransferred = math.min(input.remaining(), maxInputBytes(input.position()))
    if (amountTransferred == 0)
      return 0

    val dup = input.duplicate()
    dup.limit(dup.position() + amountTransferred)
    dst.put(dup)
    dst.position() - start
  }

  override def write(src: ByteBuffer): Int = synchronized {
    val amountTransferred = math.min(src.remaining(), maxOutputBytes(src.position()))
    val data = new Array[Byte](amountTransferred)
    src.get(data)
    amountTransferred
  }

  override def provider(): SelectorProvider = ???
  override def validOps(): Int = ???
  override def isRegistered: Boolean = ???
  override def keyFor(sel: Selector): SelectionKey = ???
  override def register(sel: Selector, ops: Int, att: Any): SelectionKey = ???
  override def configureBlocking(block: Boolean): SelectableChannel = ???
  override def isBlocking: Boolean = ???
  override def blockingLock(): AnyRef = ???
  override def implCloseChannel(): Unit = ???
}
