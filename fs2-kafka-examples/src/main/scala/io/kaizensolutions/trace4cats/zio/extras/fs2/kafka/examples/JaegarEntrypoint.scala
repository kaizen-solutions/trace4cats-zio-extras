package io.kaizensolutions.trace4cats.zio.extras.fs2.kafka.examples

import trace4cats.EntryPoint
import trace4cats.jaeger.JaegerSpanCompleter
import trace4cats.kernel.SpanSampler
import trace4cats.model.TraceProcess
import io.kaizensolutions.trace4cats.zio.extras.*
import zio.blocking.Blocking
import zio.clock.Clock
import zio.interop.catz.*
import zio.{RManaged, Task, ZManaged}

object JaegarEntrypoint {
  def entryPoint(process: TraceProcess): RManaged[Clock & Blocking, ZEntryPoint] =
    ZManaged.runtime[Clock & Blocking].flatMap { implicit rts =>
      JaegerSpanCompleter[Task](process, "localhost")
        .map(completer => EntryPoint[Task](SpanSampler.always[Task], completer))
        .toZManaged
    }
}
