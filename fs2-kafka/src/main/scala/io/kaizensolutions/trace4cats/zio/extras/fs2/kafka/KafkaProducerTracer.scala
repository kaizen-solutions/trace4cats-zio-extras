package io.kaizensolutions.trace4cats.zio.extras.fs2.kafka

import cats.data.NonEmptyList
import fs2.kafka.*
import io.janstenpickle.trace4cats.ToHeaders
import io.janstenpickle.trace4cats.model.{AttributeValue, SpanKind}
import io.kaizensolutions.trace4cats.zio.extras.{ZSpan, ZTracer}
import zio.{Exit, Fiber, Promise, ZIO}

object KafkaProducerTracer {

  /**
   * @param tracer
   *   is the ZTracer
   * @param underlying
   * @param headers
   * @param tracerAckFromBroker
   *   set to true if you plan on acknowledging the inner effect otherwise
   *   traces will be left open causing memory leaks
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
  def trace[R, E <: Throwable, K, V](
    tracer: ZTracer,
    underlying: KafkaProducer[ZIO[R, E, *], K, V],
    headers: ToHeaders = ToHeaders.all,
    tracerAckFromBroker: Boolean = true
  ): KafkaProducer[ZIO[R, E, *], K, V] =
    new KafkaProducer[ZIO[R, E, *], K, V] {
      override def produce[P](records: ProducerRecords[P, K, V]): ZIO[R, E, ZIO[R, E, ProducerResult[P, K, V]]] =
        for {
          waitSpan  <- Promise.make[Nothing, ZSpan]
          closeSpan <- Promise.make[E, Unit]
          // NOTE: We take special care to hold the span open until the inner effect (representing the broker ack is acknowledged)
          fiber <- tracer
                     .withSpan("kafka-producer-send-buffer", kind = SpanKind.Producer) { span =>
                       waitSpan.succeed(span) *> closeSpan.await
                     }
                     .fork
          span   <- waitSpan.await
          result <- tracedProduce(span, fiber, closeSpan, records, tracerAckFromBroker)
        } yield result

      private def tracedProduce[P](
        span: ZSpan,
        spanFiber: Fiber[E, Unit],
        closeSpan: Promise[E, Unit],
        records: ProducerRecords[P, K, V],
        tracerAckFromBroker: Boolean
      ): ZIO[R, E, ZIO[R, E, ProducerResult[P, K, V]]] =
        tracer
          .extractHeaders(headers)
          .flatMap { traceHeaders =>
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
            ).map(ackFromBroker =>
              tracer.locally(span) {
                // Ensure the outer span is closed once the inner ack effect is finished
                tracer.span("kafka-producer-broker-ack") {
                  enrichSpanWithError(
                    "error.message-broker-ack",
                    "error.cause-broker-ack",
                    span,
                    ackFromBroker
                  ).onExit {
                    case Exit.Success(_)     => closeSpan.succeed(())
                    case Exit.Failure(cause) => closeSpan.refailCause(cause)
                  }
                }
              }
            ).onInterrupt(spanFiber.interrupt)
              .onExit(_ =>
                // close span immediately if we don't care about tracing the acknowledgement from the broker
                // otherwise rely on the inner effect being run
                if (!tracerAckFromBroker) closeSpan.succeed(())
                else ZIO.unit
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
