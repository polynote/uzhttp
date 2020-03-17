package uzhttp.header

import uzhttp.HTTPError.BadRequest

import scala.collection.immutable.ListMap
import scala.language.implicitConversions

final class Headers private (private val mapWithLowerCaseKeys: Map[String, (String, String)]) extends CompatMap[String, String] with scala.collection.immutable.Map[String, String] with Serializable {
  override def updated[V1 >: String](key: String, value: V1): Headers = new Headers(mapWithLowerCaseKeys + (key.toLowerCase -> (key -> value.toString)))
  override def +[B1 >: String](kv: (String, B1)): Headers = updated(kv._1, kv._2)
  override def get(key: String): Option[String] = mapWithLowerCaseKeys.get(key.toLowerCase()).map(_._2)
  override def iterator: Iterator[(String, String)] = mapWithLowerCaseKeys.iterator.map {
    case (_, origKeyWithValue) => origKeyWithValue
  }
  override def removed(key: String): Headers = new Headers(mapWithLowerCaseKeys - key.toLowerCase)

  override def contains(key: String): Boolean = mapWithLowerCaseKeys.contains(key.toLowerCase())

  override def toList: List[(String, String)] = mapWithLowerCaseKeys.toList.map(_._2)

  def ++(kvs: Seq[(String, String)]): Headers = new Headers(mapWithLowerCaseKeys ++ ListMap(kvs.map(kv => kv._1.toLowerCase -> (kv._1 -> kv._2)): _*))
  def ++(headers: Headers): Headers = new Headers(mapWithLowerCaseKeys ++ headers.mapWithLowerCaseKeys)

  def +?(kv: (String, Option[String])): Headers = kv match {
    case (key, Some(v)) => this + (key -> v)
    case (_, None)      => this
  }
}

object Headers {
  def apply(kvs: (String, String)*): Headers = new Headers(ListMap(kvs: _*).map(kv => kv._1.toLowerCase -> (kv._1 -> kv._2)))
  def apply(map: Map[String, String]): Headers = new Headers(ListMap(map.toSeq: _*).map(kv => kv._1.toLowerCase -> (kv._1 -> kv._2)))

  def fromLines(lines: List[String]): Headers = apply {
    lines.map {
      str =>
        str.indexOf(':') match {
          case -1 =>
            throw BadRequest(s"No key separator in header $str")
          case n =>
            str.splitAt(n) match {
              case (k, v) => (k, v.drop(1).dropWhile(_.isWhitespace))
            }
        }
    }
  }

  implicit def fromSeq(kvs: Seq[(String, String)]): Headers = apply(kvs: _*)

  val empty: Headers = apply()

  val CacheControl: String = "Cache-Control"
  val ContentLength: String = "Content-Length"
  val ContentType: String = "Content-Type"
  val IfModifiedSince: String = "If-Modified-Since"
  val LastModified: String = "Last-Modified"
}