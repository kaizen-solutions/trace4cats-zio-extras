package io.kaizensolutions.trace4cats.zio.extras.caliban

import io.kaizensolutions.trace4cats.zio.extras.ZTracer
import zio.{URLayer, ZLayer}
import caliban.wrappers.Wrapper

final class TracedWrapper(tracer: ZTracer) {
  val all =
    new TracedExecutionWrapper(tracer).wrapper @@
      new TracedFieldWrapper(tracer).wrapper

  val field = new TracedFieldWrapper(tracer).wrapper

  val execution = new TracedExecutionWrapper(tracer).wrapper
}
object TracedWrapper {
  val all = TracedExecutionWrapper.wrapper @@ TracedFieldWrapper.wrapper

  val field: Wrapper.FieldWrapper[ZTracer] = TracedFieldWrapper.wrapper

  val execution: Wrapper.ExecutionWrapper[ZTracer] = TracedExecutionWrapper.wrapper

  val layer: URLayer[ZTracer, TracedWrapper] = ZLayer.fromFunction(new TracedWrapper(_))
}
