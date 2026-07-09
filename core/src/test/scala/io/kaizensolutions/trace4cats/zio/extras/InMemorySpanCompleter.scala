package io.kaizensolutions.trace4cats.zio.extras

import trace4cats.{EntryPoint, ToHeaders}
import trace4cats.kernel.{SpanCompleter, SpanSampler}
import trace4cats.model.{CompletedSpan, TraceProcess}
import zio.interop.catz.*
import zio.{Chunk, FiberRef, Ref, Scope, Task, UIO, URIO, ZEnvironment, ZIO, ZLayer}

class InMemorySpanCompleter(private val process: TraceProcess, private val state: Ref[Chunk[CompletedSpan]])
    extends SpanCompleter[Task] {
  override def complete(span: CompletedSpan.Builder): Task[Unit] =
    state.update(_ :+ span.build(process))

  def retrieveCollected: UIO[Chunk[CompletedSpan]] =
    state.get

  /**
   * Polls until the predicate is satisfied or the timeout expires. Unlike a
   * sleep, this returns as soon as the condition is met.
   */
  def awaitCollected(
    predicate: Chunk[CompletedSpan] => Boolean,
    timeout: zio.Duration = zio.Duration.fromMillis(5000)
  ): UIO[Chunk[CompletedSpan]] =
    state.get
      .repeatUntil(predicate)
      .timeout(timeout)
      .map(_.getOrElse(Chunk.empty))
}
object InMemorySpanCompleter {

  def retrieveCollected: URIO[InMemorySpanCompleter, Chunk[CompletedSpan]] =
    ZIO.serviceWithZIO[InMemorySpanCompleter](_.retrieveCollected)

  def awaitCollected(
    predicate: Chunk[CompletedSpan] => Boolean,
    timeout: zio.Duration = zio.Duration.fromMillis(5000)
  ): URIO[InMemorySpanCompleter, Chunk[CompletedSpan]] =
    ZIO.serviceWithZIO[InMemorySpanCompleter](_.awaitCollected(predicate, timeout))

  def entryPoint(
    process: TraceProcess,
    headers: ToHeaders = ToHeaders.standard
  ): UIO[(InMemorySpanCompleter, EntryPoint[Task])] =
    Ref
      .make(Chunk.empty[CompletedSpan])
      .map(new InMemorySpanCompleter(process, _))
      .map(completer => (completer, EntryPoint[Task](SpanSampler.always[Task], completer, headers)))

  def toZTracer(in: EntryPoint[Task]): URIO[Scope, ZTracer] = {
    val zep = new ZEntryPoint(in)
    FiberRef
      .make(ZSpan.noop)
      .map(ZTracer.make(_, zep))
  }

  def layer(serviceName: String) =
    ZLayer.scopedEnvironment[Any](
      for {
        z                      <- entryPoint(TraceProcess(serviceName))
        (completer, entrypoint) = z
        tracer                 <- toZTracer(entrypoint)
      } yield ZEnvironment(completer, tracer)
    )

  def layer0(serviceName: String) =
    ZLayer.scopedEnvironment[Any](
      for {
        z                      <- entryPoint(TraceProcess(serviceName))
        (completer, entrypoint) = z
      } yield ZEnvironment(completer, new ZEntryPoint(entrypoint))
    )
}
