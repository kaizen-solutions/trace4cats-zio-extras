package io.kaizensolutions.trace4cats.zio.extras.fs2

import trace4cats.EntryPoint
import trace4cats.jaeger.JaegerSpanCompleter
import trace4cats.kernel.SpanSampler
import trace4cats.model.TraceProcess
import io.kaizensolutions.trace4cats.zio.extras.*
import zio.interop.catz.*
import zio.{RIO, Scope, Task, ULayer, ZLayer}

object JaegarEntrypoint {
  val live: ULayer[ZEntryPoint] =
    ZLayer.scoped[Any](entryPoint(TraceProcess("fs2-example-app"))).orDie

  def entryPoint(process: TraceProcess): RIO[Scope, ZEntryPoint] =
    JaegerSpanCompleter[Task](process, "localhost")
      .map(completer => EntryPoint[Task](SpanSampler.always[Task], completer))
      .scoped

}
