package uzhttp

import uzhttp.Request.Method.{GET, POST, PUT}
import zio.ZIO


package object routing {

  type ZR[R] = ZIO[R, HTTPError, Response]

  implicit class StackingPF[R](underlying: PartialFunction[Request, ZIO[R, HTTPError, Response]]) {
    def ~(that: PartialFunction[Request, ZIO[R, HTTPError, Response]]): PartialFunction[Request, ZIO[R, HTTPError, Response]] = underlying orElse that
  }

  def else404[R]: PartialFunction[Request, ZR[R]] = {
    case _ => ZIO.succeed(Response.html("<html><body><h1>Go F yourself</h1></body></html>"))
  }

  sealed private[routing] trait Parameter {
    def underlying: String
  }

  case object `?` extends Parameter with CanAddVariablePart[Params1,Params2]  {
    val createSame: String => Params1 = new Params1(_)
    val createNext: String => Params2 = new Params2(_)
    val underlying = "/([^/]*)"
  }

  case object Remaining extends Parameter { val underlying = "/(.*)" }

  private[routing] def addToPattern(s: String): String = s

  private[routing] def addToPattern(s1: String, s2: String): String = (s1 + s2).replaceAll("[/]+", "/")

  sealed private[routing] trait CanAddStringPart[A] {
    def underlying: String
    def createSame: String => A
    def / (s: String): A= createSame(addToPattern(underlying,"/" + addToPattern(s)))
  }

  sealed private[routing] trait CanAddVariablePart[A, B] extends CanAddStringPart[A]  {
    def createNext: String => B
    def / (x: Parameter): B = createNext(addToPattern(underlying, x.underlying))
  }

  implicit final class Params0(val u: String) extends CanAddVariablePart[Params0, Params1] {
    val underlying: String = addToPattern(u)
    val createSame = new Params0(_: String)
    val createNext = new Params1(_: String)
  }

  final private[routing] case class Params1(underlying: String, createSame: String => Params1 = new Params1(_), createNext: String => Params2 = new Params2(_))
    extends CanAddVariablePart[Params1, Params2]

  final private[routing] case class Params2(underlying: String, createSame: String => Params2 = new Params2(_), createNext: String => Params3 = new Params3(_))
    extends CanAddVariablePart[Params2, Params3]

  final private[routing] case class Params3(underlying: String, createSame: String => Params3 = new Params3(_), createNext: String => Params4 = new Params4(_))
    extends CanAddVariablePart[Params3, Params4]

  final private[routing] case class Params4(underlying: String, createSame: String => Params4 = new Params4(_), createNext: String => Params5 = new Params5(_))
    extends CanAddVariablePart[Params4, Params5]

  final private[routing] case class Params5(underlying: String, createSame: String => Params5 = new Params5(_), createNext: String => Params6 = new Params6(_))
    extends CanAddVariablePart[Params5, Params6]

  final private[routing] case class Params6(underlying: String, createSame: String => Params6 = new Params6(_))
    extends CanAddStringPart[Params6]

  def get[R](f: PartialFunction[Request, ZR[R]]): PartialFunction[Request, ZR[R]] = {
    case req if req.method == GET && f.isDefinedAt(req) => f(req)
  }

  def post[R](f: PartialFunction[Request, ZR[R]]): PartialFunction[Request, ZR[R]] = {
    case req if req.method == POST && f.isDefinedAt(req) => f(req)
  }

  def put[R](f: PartialFunction[Request, ZR[R]]): PartialFunction[Request, ZR[R]] = {
    case req if req.method == PUT && f.isDefinedAt(req) => f(req)
  }

  def path[R](z: Parameter)(f: PartialFunction[(Request, String), ZR[R]] ): PartialFunction[Request, ZR[R]]  = {
    case req if z.underlying.r.unapplySeq(req.uri.getPath).isDefined && f.isDefinedAt(req, "") =>
      val regex = z.underlying.r
      req.uri.getPath match { case regex(p1) => f(req, p1) }
  }

  def path[R](z: Params0)(f: PartialFunction[Request, ZR[R]]): PartialFunction[Request, ZR[R]] = {
    case req if req.uri.getPath == z.underlying && f.isDefinedAt(req) => f(req)
  }

  def path[R](z: Params1)(f: PartialFunction[(Request, String), ZR[R]]): PartialFunction[Request, ZR[R]] =  {
    case req if z.underlying.r.unapplySeq(req.uri.getPath).isDefined && f.isDefinedAt(req, "") =>
      val regex = z.underlying.r
      req.uri.getPath match { case regex(p1) => f(req, p1) }
  }

  def path[R](z: Params2)(f: PartialFunction[(Request, String, String), ZR[R]]): PartialFunction[Request, ZR[R]] =  {
    case req if z.underlying.r.unapplySeq(req.uri.getPath).isDefined && f.isDefinedAt(req, "", "") =>
      val regex = z.underlying.r
      req.uri.getPath match { case regex(p1, p2) => f(req, p1, p2) }
  }

  def path[R](z: Params3)(f: PartialFunction[(Request, String, String, String), ZR[R]]): PartialFunction[Request, ZR[R]] =  {
    case req if z.underlying.r.unapplySeq(req.uri.getPath).isDefined && f.isDefinedAt(req, "", "", "") =>
      val regex = z.underlying.r
      req.uri.getPath match { case regex(p1, p2, p3) => f(req, p1, p2, p3) }
  }

  def path[R](z: Params4)(f: PartialFunction[(Request, String, String, String, String), ZR[R]]): PartialFunction[Request, ZR[R]] =  {
    case req if z.underlying.r.unapplySeq(req.uri.getPath).isDefined && f.isDefinedAt(req, "", "", "", "") =>
      val regex = z.underlying.r
      req.uri.getPath match { case regex(p1, p2, p3, p4) => f(req, p1, p2, p3, p4) }
  }

  def path[R](z: Params5)(f: PartialFunction[(Request, String, String, String, String, String), ZR[R]]): PartialFunction[Request, ZR[R]] =  {
    case req if z.underlying.r.unapplySeq(req.uri.getPath).isDefined && f.isDefinedAt(req, "", "", "", "", "") =>
      val regex = z.underlying.r
      req.uri.getPath match { case regex(p1, p2, p3, p4, p5) => f(req, p1, p2, p3, p4, p5) }
  }

  def path[R](z: Params6)(f: PartialFunction[(Request, String, String, String, String, String, String), ZR[R]]): PartialFunction[Request, ZR[R]] =  {
    case req if z.underlying.r.unapplySeq(req.uri.getPath).isDefined && f.isDefinedAt(req, "", "", "", "", "", "") =>
      val regex = z.underlying.r
      req.uri.getPath match { case regex(p1, p2, p3, p4, p5, p6) => f(req, p1, p2, p3, p4, p5, p6) }
  }
}