package io.kaizensolutions.trace4cats.zio.extras.ziokafka

import cats.data.NonEmptyList
import io.kaizensolutions.trace4cats.zio.extras.{ZSpan, ZTracer}
import org.apache.kafka.clients.producer.{ProducerRecord, RecordMetadata}
import org.apache.kafka.common.header.internals.RecordHeader
import org.apache.kafka.common.{Metric, MetricName}
import trace4cats.{AttributeValue, ToHeaders, TraceHeaders}
import trace4cats.model.SpanKind
import zio.{Chunk, IO, RIO, Task, UIO, ZIO, ZLayer}
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
    override def produce(record: ProducerRecord[Array[Byte], Array[Byte]]): Task[RecordMetadata] =
      produceChunk(Chunk.single(record)).map(_.head)

    override def produceAsync(record: ProducerRecord[Array[Byte], Array[Byte]]): Task[Task[RecordMetadata]] =
      produceChunkAsync(Chunk.single(record)).map(_.map(_.head))

    override def produceChunk(records: Chunk[ProducerRecord[Array[Byte], Array[Byte]]]): Task[Chunk[RecordMetadata]] =
      produceChunkAsync(records).flatten

    override def produceChunkAsync(
      records: Chunk[ProducerRecord[Array[Byte], Array[Byte]]]
    ): Task[Task[Chunk[RecordMetadata]]] =
      tracedProduceChunkAsyncBytes(tracer, underlying, toHeaders)(records)

    override def produceChunkAsyncWithFailures(
      records: Chunk[ProducerRecord[Array[Byte], Array[Byte]]]
    ): UIO[UIO[Chunk[Either[Throwable, RecordMetadata]]]] =
      tracer.withSpan("kafka-producer-send-buffer", kind = SpanKind.Producer) { span =>
        tracer
          .extractHeaders(toHeaders)
          .flatMap { traceHeaders =>
            val sendToProducerBuffer: UIO[UIO[Chunk[Either[Throwable, RecordMetadata]]]] = for {
              _                  <- enrichSpanWithTopics(records, span)
              recordsWithHeaders <- enrichRecordsWithTraceHeaders(traceHeaders, records)
              waitForAck         <- underlying.produceChunkAsyncWithFailures(recordsWithHeaders)
            } yield waitForAck

            enrichSpanWithBufferSendAndBrokerAckInfo(tracer, span, toHeaders)(sendToProducerBuffer)
          }
      }

    override def produce[R, K, V](
      record: ProducerRecord[K, V],
      keySerializer: Serializer[R, K],
      valueSerializer: Serializer[R, V]
    ): RIO[R, RecordMetadata] =
      produceChunkAsync(Chunk.single(record), keySerializer, valueSerializer).flatten.map(_.head)

    override def produce[R, K, V](
      topic: String,
      key: K,
      value: V,
      keySerializer: Serializer[R, K],
      valueSerializer: Serializer[R, V]
    ): RIO[R, RecordMetadata] =
      produce(new ProducerRecord(topic, key, value), keySerializer, valueSerializer)

    override def produceAsync[R, K, V](
      record: ProducerRecord[K, V],
      keySerializer: Serializer[R, K],
      valueSerializer: Serializer[R, V]
    ): RIO[R, Task[RecordMetadata]] =
      produceChunkAsync(Chunk.single(record), keySerializer, valueSerializer).map(_.map(_.head))

    override def produceAsync[R, K, V](
      topic: String,
      key: K,
      value: V,
      keySerializer: Serializer[R, K],
      valueSerializer: Serializer[R, V]
    ): RIO[R, Task[RecordMetadata]] =
      produceAsync(new ProducerRecord(topic, key, value), keySerializer, valueSerializer)

    override def produceChunkAsync[R, K, V](
      records: Chunk[ProducerRecord[K, V]],
      keySerializer: Serializer[R, K],
      valueSerializer: Serializer[R, V]
    ): RIO[R, Task[Chunk[RecordMetadata]]] =
      tracedProduceChunkAsync(tracer, underlying, toHeaders, keySerializer, valueSerializer)(records)

    override def produceChunk[R, K, V](
      records: Chunk[ProducerRecord[K, V]],
      keySerializer: Serializer[R, K],
      valueSerializer: Serializer[R, V]
    ): RIO[R, Chunk[RecordMetadata]] =
      produceChunkAsync(records, keySerializer, valueSerializer).flatten

    def flush: Task[Unit] = underlying.flush

    def metrics: Task[Map[MetricName, Metric]] = underlying.metrics
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
            _                  <- enrichSpanWithTopics(records, span)
            recordsWithHeaders <- enrichRecordsWithTraceHeaders(traceHeaders, records)
            waitForAck         <- underlying.produceChunkAsync(recordsWithHeaders, keySerializer, valueSerializer)
          } yield waitForAck

          enrichSpanWithBufferSendAndBrokerAckInfo(tracer, span, headers)(sendToProducerBuffer)
        }
    }

  private def tracedProduceChunkAsyncBytes(
    tracer: ZTracer,
    underlying: Producer,
    headers: ToHeaders
  )(records: Chunk[ProducerRecord[Array[Byte], Array[Byte]]]): Task[Task[Chunk[RecordMetadata]]] =
    tracer.withSpan("kafka-producer-send-buffer", kind = SpanKind.Producer) { span =>
      tracer
        .extractHeaders(headers)
        .flatMap { traceHeaders =>
          val sendToProducerBuffer = for {
            _                  <- enrichSpanWithTopics(records, span)
            recordsWithHeaders <- enrichRecordsWithTraceHeaders(traceHeaders, records)
            waitForAck         <- underlying.produceChunkAsync(recordsWithHeaders)
          } yield waitForAck

          enrichSpanWithBufferSendAndBrokerAckInfo(tracer, span, headers)(sendToProducerBuffer)
        }
    }

  private def enrichSpanWithTopics[K, V](records: Chunk[ProducerRecord[K, V]], span: ZSpan): UIO[Unit] =
    NonEmptyList
      .fromList(records.map(_.topic()).toList)
      .fold(ifEmpty = ZIO.unit)(topics => span.put("topics", AttributeValue.StringList(topics.distinct)).ignoreLogged)

  private def enrichRecordsWithTraceHeaders[K, V](
    headers: TraceHeaders,
    records: Chunk[ProducerRecord[K, V]]
  ): UIO[Chunk[ProducerRecord[K, V]]] =
    ZIO.succeed {
      val kafkaTraceHeaders = headers.values.map { case (k, v) => new RecordHeader(k.toString, v.getBytes) }.toList
      records.map { record =>
        val mutableRecordHeaders = record.headers()
        kafkaTraceHeaders.foreach(mutableRecordHeaders.add)
        record
      }
    }

  private def enrichSpanWithBufferSendAndBrokerAckInfo[R, E, B](tracer: ZTracer, span: ZSpan, headerFormat: ToHeaders)(
    sendToKafka: ZIO[R, E, IO[E, B]]
  ): ZIO[R, E, IO[E, B]] =
    enrichSpanWithError(
      "error.message-producer-buffer-send",
      "error.cause-producer-buffer-send",
      span,
      sendToKafka
    ).map(brokerAck =>
      // The outer span may be closed so to be safe, we extract the ID and use it to create a sub-span for the ack
      tracer.fromHeaders(
        headers = headerFormat.fromContext(span.context),
        kind = SpanKind.Producer,
        name = "kafka-producer-broker-ack"
      ) { span =>
        enrichSpanWithError("error.message-broker-ack", "error.cause-broker-ack", span, brokerAck)
      }
    )

  private def enrichSpanWithError[R, E, A](
    errorKey: String,
    causeKey: String,
    span: ZSpan,
    in: ZIO[R, E, A]
  ): ZIO[R, E, A] =
    in.tapError {
      case e: Throwable => span.put(errorKey, AttributeValue.StringValue(e.getLocalizedMessage)).when(span.isSampled)
      case other        => span.put(errorKey, AttributeValue.StringValue(other.toString)).when(span.isSampled)
    }.tapDefect(c => span.put(causeKey, AttributeValue.StringValue(c.prettyPrint)).when(span.isSampled))
}
