package io.kaizensolutions.trace4cats.zio.extras.fs2

import trace4cats.EntryPoint
import trace4cats.jaeger.JaegerSpanCompleter
import trace4cats.kernel.SpanSampler
import trace4cats.model.TraceProcess
import io.kaizensolutions.trace4cats.zio.extras.*
import zio.{Has, RManaged, Task, URLayer, ZLayer, ZManaged}
import zio.blocking.Blocking
import zio.clock.Clock
import zio.interop.catz.*

object JaegarEntrypoint {
  val live: URLayer[Clock & Blocking, Has[ZEntryPoint]] =
    ZLayer
      .fromManaged(
        entryPoint(TraceProcess("fs2-example-app"))
      )
      .orDie

  def entryPoint(process: TraceProcess): RManaged[Clock & Blocking, ZEntryPoint] =
    ZManaged.runtime[Clock & Blocking].flatMap { implicit rts =>
      JaegerSpanCompleter[Task](process, "localhost")
        .map(completer => EntryPoint[Task](SpanSampler.always[Task], completer))
        .toZManaged
    }
}
