package io.kaizensolutions.trace4cats.zio.extras.fs2.kafka

import cats.syntax.foldable.*
import fs2.kafka.{ConsumerRecord, Headers}
import io.kaizensolutions.trace4cats.zio.extras.{KafkaLogAnnotations, OtelSemconv, SpanRelationship, ZSpan, ZTracer}
import trace4cats.ToHeaders
import trace4cats.model.{AttributeValue, Link, SpanKind, TraceHeaders}
import zio.{RIO, ZIO}

object KafkaConsumerTracer {

  type SpanNamer[K, V] = ConsumerRecord[K, V] => String
  object SpanNamer {
    def default[K, V]: SpanNamer[K, V] = record => s"process ${record.topic}"
  }

  /**
   * Wraps a function that processes a ConsumerRecord with a span. This is meant
   * for the FS2 Kafka consumeChunks API.
   *
   * A link to the producer's creation context is always added to the consumer
   * span regardless of the chosen `SpanRelationship` mode.
   *
   * @param tracer
   * @param spanNamer
   *   function to derive the span name from the record (default: "process
   *   {topic}")
   * @param spanRelationship
   *   controls whether the consumer span is a child of the producer span
   *   (`SpanRelationship.ParentChild`) or starts a new trace context
   *   (`SpanRelationship.Link`). Default: ParentChild.
   * @param process
   * @return
   */
  def processSpannedConsumerRecord[R, K, V, Out](
    tracer: ZTracer,
    spanNamer: SpanNamer[K, V] = SpanNamer.default[K, V],
    spanRelationship: SpanRelationship = SpanRelationship.ParentChild,
    kafkaLogAnnotations: KafkaLogAnnotations = KafkaLogAnnotations.default
  )(process: (ConsumerRecord[K, V], ZSpan) => RIO[R, Out]): ConsumerRecord[K, V] => RIO[R, Out] = {
    (record: ConsumerRecord[K, V]) =>
      val traceHeaders = extractTraceHeaders(record.headers)
      val topic        = record.topic
      val partition    = record.partition
      val offset       = record.offset
      val key          = record.key.toString
      val spanName     = spanNamer(record)

      val attributes: Map[String, AttributeValue] =
        Map(
          OtelSemconv.MessagingSystem                 -> AttributeValue.StringValue("kafka"),
          OtelSemconv.MessagingOperationType          -> AttributeValue.StringValue("process"),
          OtelSemconv.MessagingOperationName          -> AttributeValue.StringValue("process"),
          OtelSemconv.MessagingDestinationName        -> AttributeValue.StringValue(topic),
          OtelSemconv.MessagingDestinationPartitionId -> AttributeValue.StringValue(partition.toString),
          OtelSemconv.MessagingKafkaOffset            -> AttributeValue.LongValue(offset),
          OtelSemconv.MessagingKafkaMessageKey        -> AttributeValue.StringValue(key)
        )

      // Always compute the link to the producer's creation context
      val producerLink = ToHeaders.standard.toContext(traceHeaders).map(ctx => Link(ctx.traceId, ctx.spanId))

      def body(span: ZSpan): RIO[R, Out] = {
        val addLink = producerLink.fold(ZIO.unit)(span.addLink)
        addLink *> span.putAll(attributes) *>
          ZIO.logAnnotate(kafkaLogAnnotations(attributes))(
            process(record, span)
          )
      }

      val traced = spanRelationship match {
        case SpanRelationship.ParentChild =>
          tracer.fromHeaders(headers = traceHeaders, name = spanName, kind = SpanKind.Consumer)(body)
        case SpanRelationship.Link =>
          tracer.withSpan(name = spanName, kind = SpanKind.Consumer)(body)
      }

      traced
  }

  def processConsumerRecord[R, K, V, Out](
    tracer: ZTracer,
    spanNamer: SpanNamer[K, V] = SpanNamer.default[K, V],
    spanRelationship: SpanRelationship = SpanRelationship.ParentChild,
    kafkaLogAnnotations: KafkaLogAnnotations = KafkaLogAnnotations.default
  )(process: ConsumerRecord[K, V] => RIO[R, Out]): ConsumerRecord[K, V] => RIO[R, Out] =
    processSpannedConsumerRecord(tracer, spanNamer, spanRelationship, kafkaLogAnnotations)((record, _) =>
      process(record)
    )

  private def extractTraceHeaders(in: Headers): TraceHeaders =
    in.toChain.foldMap(header => TraceHeaders.of(header.key() -> header.as[String]))
}
