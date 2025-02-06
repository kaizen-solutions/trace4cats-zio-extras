package doobie

import io.kaizensolutions.trace4cats.zio.extras.*
import trace4cats.EntryPoint
import trace4cats.opentelemetry.otlp.OpenTelemetryOtlpGrpcSpanCompleter
import trace4cats.kernel.SpanSampler
import trace4cats.model.TraceProcess
import zio.interop.catz.*
import zio.{RIO, Scope, Task, ULayer, ZLayer}

object OltpGrpcEntrypoint {
  val live: ULayer[ZEntryPoint] =
    ZLayer.scoped[Any](entryPoint(TraceProcess("doobie-app"))).orDie

  def entryPoint(process: TraceProcess): RIO[Scope, ZEntryPoint] =
    OpenTelemetryOtlpGrpcSpanCompleter[Task](process, "localhost")
      .map(completer => EntryPoint[Task](SpanSampler.always[Task], completer))
      .scoped
}
