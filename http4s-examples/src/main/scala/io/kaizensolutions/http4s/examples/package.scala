package io.kaizensolutions.http4s

import io.kaizensolutions.trace4cats.zio.extras.ZTracer
import zio.blocking.Blocking
import zio.clock.Clock
import zio.{Has, ZIO}

package object examples {
  type Effect[A] = ZIO[Clock & Blocking & Has[Db] & Has[ZTracer], Throwable, A]
}
