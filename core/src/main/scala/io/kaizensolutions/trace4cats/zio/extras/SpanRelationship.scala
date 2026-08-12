package io.kaizensolutions.trace4cats.zio.extras

/**
 * Controls how a consumer span relates to the producer span whose context was
 * propagated via message headers.
 *
 * In both modes, a `trace4cats.model.Link` to the producer span is always added
 * to the consumer span.
 *
 *   - `SpanRelationship.ParentChild`: The consumer span is created as a child
 *     of the producer's trace context (same trace ID). This ensures tail-based
 *     samplers at the collector keep producer and consumer spans together.
 *
 *   - `SpanRelationship.Link`: The consumer span starts a new trace context
 *     (per OTel messaging semconv) and only links back to the producer. Use
 *     this when you want independent trace IDs for producer and consumer.
 */
sealed trait SpanRelationship extends Product with Serializable
object SpanRelationship {
  case object ParentChild extends SpanRelationship
  case object Link        extends SpanRelationship
}
