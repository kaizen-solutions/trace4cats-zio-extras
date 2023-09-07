package io.kaizensolutions.trace4cats.zio.extras.ziokafka

import cats.data.NonEmptyList
import io.kaizensolutions.trace4cats.zio.extras.{ZSpan, ZTracer}
import org.apache.kafka.clients.producer.{ProducerRecord, RecordMetadata}
import org.apache.kafka.common.header.Header
import org.apache.kafka.common.header.internals.RecordHeader
import org.apache.kafka.common.{Metric, MetricName}
import trace4cats.{AttributeValue, ToHeaders}
import trace4cats.model.SpanKind
import zio.{Chunk, RIO, Task, UIO, ZIO, ZLayer}
import zio.kafka.producer.Producer
import zio.kafka.serde.Serializer

object KafkaProducerTracer {

  val layer: ZLayer[ZTracer & Producer, Nothing, Producer] =
    ZLayer.fromFunction(trace(_, _, ToHeaders.all))

  def trace(
    tracer: ZTracer,
    underlying: Producer,
    toHeaders: ToHeaders = ToHeaders.all
  ): Producer = {

    new Producer {
      def produce(record: ProducerRecord[Array[Byte], Array[Byte]]): Task[RecordMetadata] =
        produceChunkAsync(Chunk.single(record), Serializer.byteArray, Serializer.byteArray).flatten.map(_.head)

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

      def produceAsync(
        record: ProducerRecord[Array[Byte], Array[Byte]]
      ): Task[Task[RecordMetadata]] =
        produceChunkAsync(Chunk.single(record), Serializer.byteArray, Serializer.byteArray).map(_.map(_.head))

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

      def produceChunkAsync(
        records: Chunk[ProducerRecord[Array[Byte], Array[Byte]]]
      ): Task[Task[Chunk[RecordMetadata]]] =
        produceChunkAsync(records, Serializer.byteArray, Serializer.byteArray)

      def produceChunkAsync[R, K, V](
        records: Chunk[ProducerRecord[K, V]],
        keySerializer: Serializer[R, K],
        valueSerializer: Serializer[R, V]
      ): RIO[R, Task[Chunk[RecordMetadata]]] =
        tracedProduceChunkAsync(tracer, underlying, toHeaders, keySerializer, valueSerializer)(records)
          .map(_.flatMap { chunk =>
            val (errors, successes) = chunk.partitionMap(identity)
            if (errors.isEmpty) ZIO.succeed(successes)
            else ZIO.fail(errors.head)
          })

      def produceChunkAsyncWithFailures(
        records: Chunk[ProducerRecord[Array[Byte], Array[Byte]]]
      ): UIO[UIO[Chunk[Either[Throwable, RecordMetadata]]]] =
        tracedProduceChunkAsync(tracer, underlying, toHeaders, Serializer.byteArray, Serializer.byteArray)(records)
          .catchAll(e => ZIO.succeed(ZIO.succeed(Chunk.single(Left(e)))))

      def produceChunk(
        records: Chunk[ProducerRecord[Array[Byte], Array[Byte]]]
      ): Task[Chunk[RecordMetadata]] =
        produceChunkAsync(records, Serializer.byteArray, Serializer.byteArray).flatten

      def produceChunk[R, K, V](
        records: Chunk[ProducerRecord[K, V]],
        keySerializer: Serializer[R, K],
        valueSerializer: Serializer[R, V]
      ): RIO[R, Chunk[RecordMetadata]] =
        produceChunkAsync(records, keySerializer, valueSerializer).flatten

      def flush: Task[Unit] = underlying.flush

      def metrics: Task[Map[MetricName, Metric]] = underlying.metrics
    }
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
  )(records: Chunk[ProducerRecord[K, V]]): ZIO[R, Throwable, UIO[Chunk[Either[Throwable, RecordMetadata]]]] =
    tracer.withSpan("kafka-producer-send-buffer", kind = SpanKind.Producer) { span =>
      tracer
        .extractHeaders(headers)
        .flatMap { traceHeaders =>
          val sendToProducerBuffer = for {
            _ <- ZIO
                   .fromOption(NonEmptyList.fromList(records.toList))
                   .flatMap(nel => span.put("topics", AttributeValue.StringList(nel.map(_.topic()).distinct)))
                   .ignore
            kafkaTraceHeaders =
              traceHeaders.values.map { case (k, v) => new RecordHeader(k.toString, v.getBytes) }.toList
            recordsWithHeaders <-
              ZIO.foreach(records)(
                addHeaders(kafkaTraceHeaders, _).flatMap(serialize(_, keySerializer, valueSerializer))
              )
            waitForAck <- underlying.produceChunkAsyncWithFailures(recordsWithHeaders)
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
                ack.tap(chunk =>
                  ZIO.foreach(chunk)(elem =>
                    enrichSpanWithError(
                      "error.message-broker-ack",
                      "error.cause-broker-ack",
                      span,
                      ZIO.fromEither[Throwable, RecordMetadata](elem)
                    ).either
                  )
                )
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

  private def serialize[R, K, V](
    r: ProducerRecord[K, V],
    keySerializer: Serializer[R, K],
    valueSerializer: Serializer[R, V]
  ): RIO[R, ProducerRecord[Array[Byte], Array[Byte]]] =
    for {
      key   <- keySerializer.serialize(r.topic, r.headers, r.key())
      value <- valueSerializer.serialize(r.topic, r.headers, r.value())
    } yield new ProducerRecord(r.topic, r.partition(), r.timestamp(), key, value, r.headers)
}
