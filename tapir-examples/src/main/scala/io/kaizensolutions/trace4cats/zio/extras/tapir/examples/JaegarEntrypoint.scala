package io.kaizensolutions.trace4cats.zio.extras.tapir.examples

import io.janstenpickle.trace4cats.inject.EntryPoint
import io.janstenpickle.trace4cats.jaeger.JaegerSpanCompleter
import io.janstenpickle.trace4cats.kernel.SpanSampler
import io.janstenpickle.trace4cats.model.TraceProcess
import io.kaizensolutions.trace4cats.zio.extras.*
import zio.interop.catz.*
import zio.{RIO, Scope, Task, ULayer, ZLayer}

object JaegarEntrypoint {
  val live: ULayer[ZEntryPoint] = ZLayer
    .scoped[Any](entryPoint(TraceProcess("tapir-example-server-app")))
    .orDie

  def entryPoint(process: TraceProcess): RIO[Scope, ZEntryPoint] =
    JaegerSpanCompleter[Task](process, "localhost")
      .map(completer => EntryPoint[Task](SpanSampler.always[Task], completer))
      .scoped

}
