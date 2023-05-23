package io.kaizensolutions.trace4cats.zio.extras.ziokafka

import cats.data.NonEmptyList
import io.kaizensolutions.trace4cats.zio.extras.{ZSpan, ZTracer}
import org.apache.kafka.clients.producer.{ProducerRecord, RecordMetadata}
import org.apache.kafka.common.header.Header
import org.apache.kafka.common.header.internals.RecordHeader
import org.apache.kafka.common.{Metric, MetricName}
import trace4cats.{AttributeValue, ToHeaders}
import trace4cats.model.SpanKind
import zio.{Chunk, RIO, Task, ZIO, ZLayer}
import zio.kafka.producer.Producer
import zio.kafka.serde.Serializer

object KafkaProducerTracer {

  val layer: ZLayer[ZTracer & Producer, Nothing, Producer] =
    ZLayer.fromFunction(trace(_, _, ToHeaders.all))

  def trace(
    tracer: ZTracer,
    underlying: Producer,
    toHeaders: ToHeaders = ToHeaders.all
  ): Producer = new Producer {
    def produce[R, K, V](
      record: ProducerRecord[K, V],
      keySerializer: Serializer[R, K],
      valueSerializer: Serializer[R, V]
    ): RIO[R, RecordMetadata] =
      produceChunkAsync(Chunk.single(record), keySerializer, valueSerializer).flatten.map(_.head)

    def produce[R, K, V](
      topic: String,
      key: K,
      value: V,
      keySerializer: Serializer[R, K],
      valueSerializer: Serializer[R, V]
    ): RIO[R, RecordMetadata] =
      produce(new ProducerRecord(topic, key, value), keySerializer, valueSerializer)

    def produceAsync[R, K, V](
      record: ProducerRecord[K, V],
      keySerializer: Serializer[R, K],
      valueSerializer: Serializer[R, V]
    ): RIO[R, Task[RecordMetadata]] =
      produceChunkAsync(Chunk.single(record), keySerializer, valueSerializer).map(_.map(_.head))

    def produceAsync[R, K, V](
      topic: String,
      key: K,
      value: V,
      keySerializer: Serializer[R, K],
      valueSerializer: Serializer[R, V]
    ): RIO[R, Task[RecordMetadata]] =
      produceAsync(new ProducerRecord(topic, key, value), keySerializer, valueSerializer)

    def produceChunkAsync[R, K, V](
      records: Chunk[ProducerRecord[K, V]],
      keySerializer: Serializer[R, K],
      valueSerializer: Serializer[R, V]
    ): RIO[R, Task[Chunk[RecordMetadata]]] =
      tracedProduceChunkAsync(tracer, underlying, toHeaders, keySerializer, valueSerializer)(records)

    def produceChunk[R, K, V](
      records: Chunk[ProducerRecord[K, V]],
      keySerializer: Serializer[R, K],
      valueSerializer: Serializer[R, V]
    ): RIO[R, Chunk[RecordMetadata]] =
      produceChunkAsync(records, keySerializer, valueSerializer).flatten

    def flush: Task[Unit] = underlying.flush

    def metrics: Task[Map[MetricName, Metric]] = underlying.metrics
  }

  private def addHeaders[A, B](headers: List[Header], record: ProducerRecord[A, B]): Task[ProducerRecord[A, B]] =
    ZIO.attempt {
      val mutableHeaders = record.headers()
      headers.foreach {
        mutableHeaders.add
      }
      record
    }

  private def tracedProduceChunkAsync[R, K, V](
    tracer: ZTracer,
    underlying: Producer,
    headers: ToHeaders,
    keySerializer: Serializer[R, K],
    valueSerializer: Serializer[R, V]
  )(records: Chunk[ProducerRecord[K, V]]): RIO[R, Task[Chunk[RecordMetadata]]] =
    tracer.withSpan("kafka-producer-send-buffer", kind = SpanKind.Producer) { span =>
      tracer
        .extractHeaders(headers)
        .flatMap { traceHeaders =>
          val sendToProducerBuffer = for {
            _ <- ZIO
                   .fromOption(NonEmptyList.fromList(records.toList))
                   .flatMap(nel => span.put("topics", AttributeValue.StringList(nel.map(_.topic()))))
                   .ignore
            kafkaTraceHeaders =
              traceHeaders.values.map { case (k, v) => new RecordHeader(k.toString, v.getBytes) }.toList
            recordsWithHeaders <- ZIO.foreach(records)(addHeaders(kafkaTraceHeaders, _))
            waitForAck         <- underlying.produceChunkAsync(recordsWithHeaders, keySerializer, valueSerializer)
          } yield waitForAck

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
      .tapError(e => span.put(errorKey, AttributeValue.StringValue(e.getLocalizedMessage)).when(span.isSampled))
      .tapDefect(c => span.put(causeKey, AttributeValue.StringValue(c.prettyPrint)).when(span.isSampled))
}
