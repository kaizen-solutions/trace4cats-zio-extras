package io.kaizensolutions.trace4cats.zio.extras.fs2.kafka.examples

import io.janstenpickle.trace4cats.inject.EntryPoint
import io.janstenpickle.trace4cats.jaeger.JaegerSpanCompleter
import io.janstenpickle.trace4cats.kernel.SpanSampler
import io.janstenpickle.trace4cats.model.TraceProcess
import io.kaizensolutions.trace4cats.zio.extras.*
import zio.interop.catz.*
import zio.{RIO, Scope, Task}

object JaegarEntrypoint {
  def entryPoint(process: TraceProcess): RIO[Scope, ZEntryPoint] =
    JaegerSpanCompleter[Task](process, "localhost")
      .map(completer => EntryPoint[Task](SpanSampler.always[Task], completer))
      .scoped
}
