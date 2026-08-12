package io.kaizensolutions.trace4cats.zio.extras

import cats.Show
import trace4cats.model.{SpanContext, SpanId, TraceId}
import zio.{LogAnnotation, ZIO}

trait LogContextExtractor {
  def apply(spanContext: SpanContext): Set[LogAnnotation]

  def enrichLogs[R, E, A](spanContext: SpanContext)(fa: ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.logAnnotate(apply(spanContext))(fa)
}

object LogContextExtractor {

  /**
   * The default extractor creates logs that are compatible with Opentelemetry
   * @see
   *   [[https://opentelemetry.io/docs/specs/otel/compatibility/logging_trace_context/]]
   */
  def default: LogContextExtractor = (spanContext: SpanContext) =>
    Set(
      LogAnnotation("trace_id", Show[TraceId].show(spanContext.traceId)),
      LogAnnotation("span_id", Show[SpanId].show(spanContext.spanId))
    )

  val none: LogContextExtractor = _ => Set.empty
}
