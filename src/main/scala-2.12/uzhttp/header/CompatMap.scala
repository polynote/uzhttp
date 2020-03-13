package uzhttp.header

trait CompatMap[K, V] extends scala.collection.immutable.Map[K, V] {
  def removed(key: K): Map[K, V]
  final def -(key: K): Map[K, V] = removed(key)
}