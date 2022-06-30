package io.kaizensolutions.trace4cats.zio.extras.fs2.kafka

import cats.data.NonEmptyList
import fs2.kafka.*
import io.janstenpickle.trace4cats.ToHeaders
import io.janstenpickle.trace4cats.model.AttributeValue
import io.kaizensolutions.trace4cats.zio.extras.{ZSpan, ZTracer}
import org.apache.kafka.common.{Metric, MetricName}
import zio.ZIO
import zio.blocking.Blocking
import zio.clock.Clock

object KafkaProducerTracer {
  def traceMetrics[R <: Clock & Blocking, E <: Throwable, K, V](
    tracer: ZTracer,
    underlying: KafkaProducer.Metrics[ZIO[R, E, *], K, V],
    headers: ToHeaders = ToHeaders.all
  ): KafkaProducer.Metrics[ZIO[R, E, *], K, V] =
    new KafkaProducer.Metrics[ZIO[R, E, *], K, V] {
      override def produce[P](records: ProducerRecords[P, K, V]): ZIO[R, E, ZIO[R, E, ProducerResult[P, K, V]]] =
        tracedProduce[R, E, K, V, P](tracer, underlying, headers)(records)

      override def metrics: ZIO[R, E, Map[MetricName, Metric]] =
        tracer.withSpan("kafka-producer-metrics")(span =>
          enrichSpanWithError("error.message", "error.cause", span, underlying.metrics)
        )
    }

  def trace[R <: Clock & Blocking, E <: Throwable, K, V](
    tracer: ZTracer,
    underlying: KafkaProducer[ZIO[R, E, *], K, V],
    headers: ToHeaders = ToHeaders.all
  ): KafkaProducer[ZIO[R, E, *], K, V] =
    new KafkaProducer[ZIO[R, E, *], K, V] {
      override def produce[P](records: ProducerRecords[P, K, V]): ZIO[R, E, ZIO[R, E, ProducerResult[P, K, V]]] =
        tracedProduce[R, E, K, V, P](tracer, underlying, headers)(records)
    }

  private def tracedProduce[R <: Clock & Blocking, E <: Throwable, K, V, P](
    tracer: ZTracer,
    underlying: KafkaProducer[ZIO[R, E, *], K, V],
    headers: ToHeaders
  )(records: ProducerRecords[P, K, V]): ZIO[R, E, ZIO[R, E, ProducerResult[P, K, V]]] =
    tracer.withSpan("kafka-producer-send-buffer") { span =>
      tracer.extractHeaders(headers).flatMap { traceHeaders =>
        val enrichSpanWithTopics =
          if (span.isSampled)
            NonEmptyList
              .fromList(records.records.map(_.topic).toList)
              .fold(ifEmpty = ZIO.unit)(topics => span.put("topics", AttributeValue.StringList(topics)))
          else ZIO.unit

        val kafkaTraceHeaders =
          Headers.fromIterable(traceHeaders.values.map { case (k, v) => Header(k.toString, v) })
        val recordsWithTraceHeaders =
          records.records.map(record => record.withHeaders(record.headers.concat(kafkaTraceHeaders)))

        val sendToProducerBuffer =
          enrichSpanWithTopics *> underlying.produce(ProducerRecords(recordsWithTraceHeaders, records.passthrough))

        enrichSpanWithError(
          "error.message-producer-buffer-send",
          "error.cause-producer-buffer-send",
          span,
          sendToProducerBuffer
        ).map(ack =>
          tracer.locally(span) {
            tracer.span("kafka-producer-broker-ack") {
              enrichSpanWithError("error.message-broker-ack", "error.cause-broker-ack", span, ack)
            }
          }
        )
      }
    }

  private def enrichSpanWithError[R, E <: Throwable, A](
    errorKey: String,
    causeKey: String,
    span: ZSpan,
    in: ZIO[R, E, A]
  ): ZIO[R, E, A] =
    in
      .tapError(e =>
        if (span.isSampled) span.put(errorKey, AttributeValue.StringValue(e.getLocalizedMessage))
        else ZIO.unit
      )
      .tapCause(c =>
        if (span.isSampled) span.put(causeKey, AttributeValue.StringValue(c.prettyPrint))
        else ZIO.unit
      )
}
