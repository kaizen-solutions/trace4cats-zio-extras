package io.kaizensolutions.trace4cats.zio.extras.fs2.kafka.examples

import trace4cats.EntryPoint
import trace4cats.opentelemetry.otlp.OpenTelemetryOtlpGrpcSpanCompleter
import trace4cats.kernel.SpanSampler
import trace4cats.model.TraceProcess
import io.kaizensolutions.trace4cats.zio.extras.*
import zio.interop.catz.*
import zio.{RIO, Scope, Task}

object OltpGrpcEntrypoint {
  def entryPoint(process: TraceProcess): RIO[Scope, ZEntryPoint] =
    OpenTelemetryOtlpGrpcSpanCompleter[Task](process, "localhost")
      .map(completer => EntryPoint[Task](SpanSampler.always[Task], completer))
      .scoped
}
