package uzhttp.server

import zio._

object TestRuntime {
  val runtime: Runtime[ZEnv] = Runtime.default
}
