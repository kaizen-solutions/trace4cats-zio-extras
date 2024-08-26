package io.kaizensolutions.trace4cats.zio.extras.caliban

import caliban.{CalibanError, ResponseValue}
import caliban.execution.FieldInfo
import caliban.wrappers.Wrapper.FieldWrapper
import zio.query.ZQuery
import io.kaizensolutions.trace4cats.zio.extras.ZTracer
import io.kaizensolutions.trace4cats.zio.extras.zquery.*
import trace4cats.model.SpanStatus

final class TracedFieldWrapper(tracer: ZTracer) {
  val wrapper = new FieldWrapper[Any] {
    def wrap[R <: Any](
      query: ZQuery[R, CalibanError.ExecutionError, ResponseValue],
      info: FieldInfo
    ): ZQuery[R, CalibanError.ExecutionError, ResponseValue] = {
      tracer.withSpanZQuery(info.name)(span =>
        query.foldCauseQuery(
          cause => ZQuery.fromZIO(span.setStatus(SpanStatus.Internal(cause.prettyPrint))) *> ZQuery.failCause(cause),
          value => ZQuery.succeed(value)
        )
      )
    }
  }
}
object TracedFieldWrapper {
  val wrapper: FieldWrapper[ZTracer] = new FieldWrapper[ZTracer] {
    def wrap[R <: ZTracer](
      query: ZQuery[R, CalibanError.ExecutionError, ResponseValue],
      info: FieldInfo
    ): ZQuery[R, CalibanError.ExecutionError, ResponseValue] = {
      ZTracer.withSpanZQuery(info.name)(span =>
        query.foldCauseQuery(
          cause => ZQuery.fromZIO(span.setStatus(SpanStatus.Internal(cause.prettyPrint))) *> ZQuery.failCause(cause),
          value => ZQuery.succeed(value)
        )
      )
    }
  }
}
