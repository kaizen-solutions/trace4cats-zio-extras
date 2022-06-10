package io.kaizensolutions.trace4cats.zio.extras

import zio.{FiberRef, Has, UIO, ULayer, URIO, ZIO, ZLayer}

/**
 * This keeps track of each element's Span as each element in the stream is
 * associated with a TraceHeader Adapted from ZIO 2.x ZState. This is
 * effectively a new-type over FiberRef[ZSpan]
 *
 * The only downside is if you use any grouping operators on the ZStream then
 * all fiber state is wiped
 */
trait StreamElementTracer {
  val get: UIO[ZSpan]
  def set(in: ZSpan): UIO[Unit]
}
object StreamElementTracer {
  val layer: ULayer[Has[StreamElementTracer]] =
    ZLayer.fromManaged(ZSpan.noop.mapM(make))

  def make(span: ZSpan): UIO[StreamElementTracer] =
    FiberRef
      .make(span)
      .map(fiberRef =>
        new StreamElementTracer {
          override val get: UIO[ZSpan]           = fiberRef.get
          override def set(in: ZSpan): UIO[Unit] = fiberRef.set(in)
        }
      )

  val get: URIO[Has[StreamElementTracer], ZSpan]           = ZIO.serviceWith[StreamElementTracer](_.get)
  def set(in: ZSpan): URIO[Has[StreamElementTracer], Unit] = ZIO.serviceWith(_.set(in))
}
