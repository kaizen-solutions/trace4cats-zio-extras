package io.kaizensolutions.trace4cats.zio.extras

import io.janstenpickle.trace4cats.ToHeaders
import io.janstenpickle.trace4cats.inject.EntryPoint
import io.janstenpickle.trace4cats.kernel.{SpanCompleter, SpanSampler}
import io.janstenpickle.trace4cats.model.{CompletedSpan, TraceProcess}
import zio.blocking.Blocking
import zio.clock.Clock
import zio.interop.catz.*
import zio.{FiberRef, Queue, Task, UIO, ZIO}

class InMemorySpanCompleter(private val process: TraceProcess, private val state: Queue[CompletedSpan])
    extends SpanCompleter[Task] {
  override def complete(span: CompletedSpan.Builder): Task[Unit] =
    state.offer(span.build(process)).unit

  def retrieveCollected: UIO[List[CompletedSpan]] =
    state.takeAll
}
object InMemorySpanCompleter {
  def entryPoint(
    process: TraceProcess,
    headers: ToHeaders = ToHeaders.standard
  ): ZIO[Clock & Blocking, Nothing, (InMemorySpanCompleter, EntryPoint[Task])] = {
    ZIO.runtime[Clock & Blocking].flatMap { implicit rts =>
      Queue
        .unbounded[CompletedSpan]
        .map(new InMemorySpanCompleter(process, _))
        .map(completer => (completer, EntryPoint[Task](SpanSampler.always[Task], completer, headers)))
    }
  }

  def toZTracer(in: EntryPoint[Task]): UIO[ZTracer] = {
    val zep = new ZEntryPoint(in)
    FiberRef
      .make(None: Option[ZSpan])
      .map(spanRef => ZTracer.make(spanRef, zep))
  }
}
