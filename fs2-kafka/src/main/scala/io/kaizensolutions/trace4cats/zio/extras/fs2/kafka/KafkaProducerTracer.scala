package io.kaizensolutions.trace4cats.zio.extras.fs2.kafka

import cats.data.NonEmptyList
import fs2.kafka.*
import trace4cats.ToHeaders
import trace4cats.model.{AttributeValue, SpanKind}
import io.kaizensolutions.trace4cats.zio.extras.{ZSpan, ZTracer}
import org.apache.kafka.common.{Metric, MetricName}
import zio.ZIO

object KafkaProducerTracer {

  /**
   * @param tracer
   *   is the ZTracer
   * @param underlying
   * @param headers
   * @tparam R
   *   is the ZIO environment type
   * @tparam E
   *   is the ZIO error type
   * @tparam K
   *   is the Kafka producer key
   * @tparam V
   *   is the Kafka producer value
   * @return
   */
  def traceMetrics[R, E <: Throwable, K, V](
    tracer: ZTracer,
    underlying: KafkaProducer.Metrics[ZIO[R, E, *], K, V],
    headers: ToHeaders = ToHeaders.all
  ): KafkaProducer.Metrics[ZIO[R, E, *], K, V] =
    new KafkaProducer.Metrics[ZIO[R, E, *], K, V] {
      override def produce(records: ProducerRecords[K, V]): ZIO[R, E, ZIO[R, E, ProducerResult[K, V]]] =
        tracedProduce[R, E, K, V](tracer, underlying, headers)(records)

      override def metrics: ZIO[R, E, Map[MetricName, Metric]] =
        tracer.withSpan("kafka-producer-metrics")(span =>
          enrichSpanWithError("error.message", "error.cause", span, underlying.metrics)
        )
    }

  def trace[R, E <: Throwable, K, V](
    tracer: ZTracer,
    underlying: KafkaProducer[ZIO[R, E, *], K, V],
    headers: ToHeaders = ToHeaders.all
  ): KafkaProducer[ZIO[R, E, *], K, V] =
    new KafkaProducer[ZIO[R, E, *], K, V] {
      override def produce(records: ProducerRecords[K, V]): ZIO[R, E, ZIO[R, E, ProducerResult[K, V]]] =
        tracedProduce[R, E, K, V](tracer, underlying, headers)(records)
    }

  private def tracedProduce[R, E <: Throwable, K, V](
    tracer: ZTracer,
    underlying: KafkaProducer[ZIO[R, E, *], K, V],
    headers: ToHeaders
  )(records: ProducerRecords[K, V]): ZIO[R, E, ZIO[R, E, ProducerResult[K, V]]] =
    tracer.withSpan("kafka-producer-send-buffer", kind = SpanKind.Producer) { span =>
      tracer
        .extractHeaders(headers)
        .flatMap { traceHeaders =>
          val enrichSpanWithTopics =
            NonEmptyList
              .fromList(records.map(_.topic).toList.distinct)
              .fold(ifEmpty = ZIO.unit)(topics => span.put("topics", AttributeValue.StringList(topics)))
              .when(span.isSampled)

          val kafkaTraceHeaders =
            Headers.fromIterable(traceHeaders.values.map { case (k, v) => Header(k.toString, v) })
          val recordsWithTraceHeaders =
            records.map(record => record.withHeaders(record.headers.concat(kafkaTraceHeaders)))

          val sendToProducerBuffer =
            enrichSpanWithTopics *> underlying.produce(ProducerRecords(recordsWithTraceHeaders))

          enrichSpanWithError(
            "error.message-producer-buffer-send",
            "error.cause-producer-buffer-send",
            span,
            sendToProducerBuffer
          )
            .map(ack =>
              // The outer span may be closed so to be safe, we extract the ID and use it to create a sub-span for the ack
              tracer.fromHeaders(
                headers = headers.fromContext(span.context),
                kind = SpanKind.Producer,
                name = "kafka-producer-broker-ack"
              ) { span =>
                enrichSpanWithError("error.message-broker-ack", "error.cause-broker-ack", span, ack)
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
      .tapDefect(c =>
        if (span.isSampled) span.put(causeKey, AttributeValue.StringValue(c.prettyPrint))
        else ZIO.unit
      )
}
