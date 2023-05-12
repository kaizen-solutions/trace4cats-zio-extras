package io.kaizensolutions.trace4cats.zio.extras

import trace4cats.ToHeaders
import trace4cats.EntryPoint
import trace4cats.kernel.{SpanCompleter, SpanSampler}
import trace4cats.model.{CompletedSpan, TraceProcess}
import zio.interop.catz.*
import zio.{Chunk, FiberRef, Queue, Scope, Task, UIO, URIO, ZEnvironment, ZLayer}

class InMemorySpanCompleter(private val process: TraceProcess, private val state: Queue[CompletedSpan])
    extends SpanCompleter[Task] {
  override def complete(span: CompletedSpan.Builder): Task[Unit] =
    state.offer(span.build(process)).unit

  def retrieveCollected: UIO[Chunk[CompletedSpan]] =
    state.takeAll
}
object InMemorySpanCompleter {
  def entryPoint(
    process: TraceProcess,
    headers: ToHeaders = ToHeaders.standard
  ): UIO[(InMemorySpanCompleter, EntryPoint[Task])] =
    Queue
      .unbounded[CompletedSpan]
      .map(new InMemorySpanCompleter(process, _))
      .map(completer => (completer, EntryPoint[Task](SpanSampler.always[Task], completer, headers)))

  def toZTracer(in: EntryPoint[Task]): URIO[Scope, ZTracer] = {
    val zep = new ZEntryPoint(in)
    FiberRef
      .make(ZSpan.noop)
      .map(ZTracer.make(_, zep))
  }

  def layer(serviceName: String) = {
    ZLayer.scopedEnvironment[Any](
      for {
        z <- entryPoint(TraceProcess(serviceName))
        (completer, entrypoint) = z
        tracer <- toZTracer(entrypoint)
      } yield ZEnvironment(completer, tracer)
    )

  }
}
