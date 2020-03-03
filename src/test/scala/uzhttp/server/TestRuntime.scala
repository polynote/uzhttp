package uzhttp.server

import zio.{Runtime, ZEnv}
import zio.clock.Clock
import zio.console.Console
import zio.system.System
import zio.random.Random
import zio.blocking.Blocking

object TestRuntime {
  val runtime: Runtime[ZEnv] = Runtime.default
}
