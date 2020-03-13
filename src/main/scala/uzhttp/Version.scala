package uzhttp

import uzhttp.HTTPError.BadRequest


sealed abstract class Version(val string: String)
object Version {
  case object Http09 extends Version("0.9")
  case object Http10 extends Version("1.0")
  case object Http11 extends Version("1.1")

  def parseEither(str: String): Either[BadRequest, Version] = str.slice(5, 8) match {
    case "0.9" => Right(Http09)
    case "1.0" => Right(Http10)
    case "1.1" => Right(Http11)
    case _     => Left(BadRequest("Invalid HTTP version identifier"))
  }
}
