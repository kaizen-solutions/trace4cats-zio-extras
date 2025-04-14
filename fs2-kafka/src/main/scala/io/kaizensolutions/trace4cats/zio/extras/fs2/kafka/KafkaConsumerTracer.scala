package io.kaizensolutions.trace4cats.zio.extras.fs2.kafka

import cats.syntax.show.*
import cats.syntax.foldable.*
import fs2.kafka.{ConsumerRecord, Headers}
import io.kaizensolutions.trace4cats.zio.extras.{ZSpan, ZTracer}
import trace4cats.model.{AttributeValue, SpanKind, TraceHeaders}
import zio.{RIO, ZIOAspect}

object KafkaConsumerTracer {

  /**
   * Wraps a function that processes a ConsumerRecord with a span. This is meant
   * for the FS2 Kafka consumeChunks API
   *
   * @param tracer
   * @param spanName
   * @param process
   * @return
   */
  def processSpannedConsumerRecord[R, K, V, Out](
    tracer: ZTracer,
    spanName: String
  )(process: (ConsumerRecord[K, V], ZSpan) => RIO[R, Out]): ConsumerRecord[K, V] => RIO[R, Out] = {
    (record: ConsumerRecord[K, V]) =>
      val traceHeaders = extractTraceHeaders(record.headers)
      val topic        = record.topic
      val partition    = record.partition
      val offset       = record.offset
      val timestamp    = record.timestamp
      val key          = record.key.toString

      val attributes: Map[String, AttributeValue] =
        Map(
          "kafka.topic"           -> AttributeValue.StringValue(topic),
          "kafka.partition"       -> AttributeValue.LongValue(partition.toLong),
          "kafka.offset"          -> AttributeValue.LongValue(offset),
          "kafka.create.time"     -> AttributeValue.LongValue(timestamp.createTime.getOrElse(0L)),
          "kafka.log.append.time" -> AttributeValue.LongValue(timestamp.logAppendTime.getOrElse(0L)),
          "kafka.key"             -> AttributeValue.StringValue(key)
        )

      tracer.fromHeaders(headers = traceHeaders, name = spanName, kind = SpanKind.Consumer) { span =>
        span.putAll(attributes) *> process(record, span) @@ ZIOAspect.annotated(
          attributes.view.mapValues(_.show).toSeq*
        )
      }
  }

  def processConsumerRecord[R, K, V, Out](
    tracer: ZTracer,
    spanName: String
  )(process: ConsumerRecord[K, V] => RIO[R, Out]): ConsumerRecord[K, V] => RIO[R, Out] =
    processSpannedConsumerRecord(tracer, spanName)((record, _) => process(record))

  private def extractTraceHeaders(in: Headers): TraceHeaders =
    in.toChain.foldMap(header => TraceHeaders.of(header.key() -> header.as[String]))
}
