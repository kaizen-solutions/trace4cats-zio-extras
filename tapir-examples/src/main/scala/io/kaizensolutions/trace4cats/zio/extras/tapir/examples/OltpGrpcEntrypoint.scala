package io.kaizensolutions.trace4cats.zio.extras.tapir.examples

import trace4cats.EntryPoint
import trace4cats.kernel.SpanSampler
import trace4cats.model.TraceProcess
import trace4cats.opentelemetry.otlp.OpenTelemetryOtlpGrpcSpanCompleter
import io.kaizensolutions.trace4cats.zio.extras.*
import zio.interop.catz.*
import zio.{RIO, Scope, Task, ULayer, ZLayer}

object OltpGrpcEntrypoint {
  val live: ULayer[ZEntryPoint] = ZLayer
    .scoped[Any](entryPoint(TraceProcess("tapir-example-server-app")))
    .orDie

  def entryPoint(process: TraceProcess): RIO[Scope, ZEntryPoint] =
    OpenTelemetryOtlpGrpcSpanCompleter[Task](process, "localhost")
      .map(completer => EntryPoint[Task](SpanSampler.always[Task], completer))
      .scoped
} 