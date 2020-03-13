package uzhttp

import uzhttp.Status.Inst

trait Status {
  def statusCode: Int
  def statusText: String

  override def toString: String = s"$statusCode $statusText"
}

object Status {
  class Inst(val statusCode: Int, val statusText: String) extends Status with Serializable
  def apply(statusCode: Int, statusText: String): Status = new Inst(statusCode, statusText)

  object Continue extends Inst(100, "Continue")
  object SwitchingProtocols extends Inst(101, "Switching Protocols")

  object Ok extends Inst(200, "OK")
  object Created extends Inst(201, "Created")
  object Accepted extends Inst(202, "Accepted")

  object MultipleChoices extends Inst(300, "Multiple Choices")
  object MovedPermanently extends Inst(301, "Moved Permanently")
  object Found extends Inst(302, "Found")
  object SeeOther extends Inst(302, "See Other")
  object NotModified extends Inst(304, "Not Modified")
  object TemporaryRedirect extends Inst(307, "Temporary Redirect")
  object PermanentRedirect extends Inst(308, "Permanent Redirect")
}

