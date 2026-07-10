package io.kaizensolutions.trace4cats.zio.extras.fs2.kafka

import cats.syntax.show.*
import cats.syntax.foldable.*
import fs2.kafka.{ConsumerRecord, Headers}
import io.kaizensolutions.trace4cats.zio.extras.{OtelSemconv, ZSpan, ZTracer}
import trace4cats.ToHeaders
import trace4cats.model.{AttributeValue, Link, SpanKind, TraceHeaders}
import zio.{RIO, ZIO, ZIOAspect}

object KafkaConsumerTracer {

  type SpanNamer[K, V] = ConsumerRecord[K, V] => String
  object SpanNamer {
    def default[K, V]: SpanNamer[K, V] = record => s"process ${record.topic}"
  }

  /**
   * Wraps a function that processes a ConsumerRecord with a span. This is meant
   * for the FS2 Kafka consumeChunks API.
   *
   * Per the OTel messaging semconv, the consumer "Process" span uses a link to
   * the producer's creation context rather than a parent-child relationship.
   *
   * Note: commits are not traced here because they happen externally (e.g. via
   * CommitNow in consumeChunk, or commitBatchWithin). This function only
   * instruments the processing of individual records.
   *
   * @param tracer
   * @param spanNamer
   *   function to derive the span name from the record (default: "process
   *   {topic}")
   * @param process
   * @return
   */
  def processSpannedConsumerRecord[R, K, V, Out](
    tracer: ZTracer,
    spanNamer: SpanNamer[K, V] = SpanNamer.default[K, V]
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

      // Per semconv: consumer "Process" span links to the producer's creation context
      // instead of using it as a parent. This preserves the consumer's own trace context.
      val producerLink = ToHeaders.standard.toContext(traceHeaders).map(ctx => Link(ctx.traceId, ctx.spanId))

      tracer.withSpan(name = spanName, kind = SpanKind.Consumer) { span =>
        val addLink = producerLink.fold(ZIO.unit)(span.addLink)
        addLink *> span.putAll(attributes) *> process(record, span)
      } @@ ZIOAspect.annotated(attributes.view.mapValues(_.show).toSeq*)
  }

  def processConsumerRecord[R, K, V, Out](
    tracer: ZTracer,
    spanNamer: SpanNamer[K, V] = SpanNamer.default[K, V]
  )(process: ConsumerRecord[K, V] => RIO[R, Out]): ConsumerRecord[K, V] => RIO[R, Out] =
    processSpannedConsumerRecord(tracer, spanNamer)((record, _) => process(record))

  private def extractTraceHeaders(in: Headers): TraceHeaders =
    in.toChain.foldMap(header => TraceHeaders.of(header.key() -> header.as[String]))
}
