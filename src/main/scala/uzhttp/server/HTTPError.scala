package uzhttp.server

abstract class HTTPError(val statusCode: Int, val statusText: String, msg: String) extends Throwable(msg) with Status

object HTTPError {
  def unapply(err: Throwable): Option[(Int, String)] = err match {
    case err: HTTPError => Some(((err.statusCode, err.getMessage)))
    case _ => None
  }
}

abstract class HTTPErrorWithCause(statusCode: Int, statusText: String, msg: String) extends HTTPError(statusCode, statusText, msg) {
  def cause: Option[Throwable]
  cause.foreach(initCause)
}

final case class BadRequest(message: String) extends HTTPError(400, "Bad Request", message)
final case class Unauthorized(message: String) extends HTTPError(401, "Unauthorized", message)

final case class Forbidden(message: String) extends HTTPError(403, "Forbidden", message)
final case class NotFound(uri: String) extends HTTPError(404, "Not Found", s"The requested URI $uri was not found on this server.")
final case class MethodNotAllowed(message: String) extends HTTPError(405, "Method Not Allowed", message)
final case class RequestTimeout(message: String) extends HTTPError(408, "Request Timeout", message)
final case class PayloadTooLarge(message: String) extends HTTPError(413, "Payload Too Large", message)

final case class InternalServerError(message: String, cause: Option[Throwable] = None) extends HTTPErrorWithCause(500, "Internal Server Error", message)

final case class NotImplemented(message: String) extends HTTPError(501, "Not Implemented", message)
final case class HTTPVersionNotSupported(message: String) extends HTTPError(505, "HTTP Version Not Supported", message)