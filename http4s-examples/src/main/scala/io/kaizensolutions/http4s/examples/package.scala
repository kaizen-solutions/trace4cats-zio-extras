package io.kaizensolutions.http4s

import io.kaizensolutions.trace4cats.zio.extras.ZTracer
import zio.ZIO

package object examples {
  type Effect[A] = ZIO[Db & ZTracer, Throwable, A]
}
