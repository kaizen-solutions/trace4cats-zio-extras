package com.calvin

import io.kaizensolutions.trace4cats.zio.extras.ZTracer
import zio.blocking.Blocking
import zio.clock.Clock
import zio.{Has, RIO}

package object examples {
  type Deps      = Clock with Blocking with Has[Db] with Has[ZTracer]
  type Effect[A] = RIO[Deps, A]
}
