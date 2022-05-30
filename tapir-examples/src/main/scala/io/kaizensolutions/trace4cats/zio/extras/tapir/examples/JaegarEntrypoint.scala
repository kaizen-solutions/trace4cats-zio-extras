package io.kaizensolutions.trace4cats.zio.extras.tapir.examples

import io.janstenpickle.trace4cats.inject.EntryPoint
import io.janstenpickle.trace4cats.jaeger.JaegerSpanCompleter
import io.janstenpickle.trace4cats.kernel.SpanSampler
import io.janstenpickle.trace4cats.model.TraceProcess
import io.kaizensolutions.trace4cats.zio.extras.*
import zio.blocking.Blocking
import zio.clock.Clock
import zio.interop.catz.*
import zio.{Has, RManaged, Task, URLayer, ZLayer, ZManaged}

object JaegarEntrypoint {
  val live: URLayer[Clock & Blocking, Has[ZEntryPoint]] = ZLayer
    .fromManaged(
      entryPoint(TraceProcess("tapir-example-server-app"))
    )
    .orDie

  def entryPoint(process: TraceProcess): RManaged[Clock & Blocking, ZEntryPoint] =
    ZManaged.runtime[Clock & Blocking].flatMap { implicit rts =>
      JaegerSpanCompleter[Task](process, "localhost")
        .map(completer => EntryPoint[Task](SpanSampler.always[Task], completer))
        .toZManaged
    }
}