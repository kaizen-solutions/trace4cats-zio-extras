package io.kaizensolutions.trace4cats.zio.extras.ziokafka

import io.kaizensolutions.trace4cats.zio.extras.*
import trace4cats.model.AttributeValue
import trace4cats.{SpanKind, ToHeaders}
import zio.kafka.consumer.*
import org.apache.kafka.clients.consumer.ConsumerRecord as KafkaConsumerRecord
import zio.kafka.serde.Deserializer
import zio.stream.ZStream
import izumi.reflect.Tag
import zio.*

object KafkaConsumerTracer {
  type SpanNamer[K, V] = CommittableRecord[K, V] => String
  object SpanNamer {
    def default[K, V]: SpanNamer[K, V] = record => s"process ${record.record.topic}"
  }

  def traceConsumerStream[R, K, V](
    tracer: ZTracer,
    stream: ZStream[R, Throwable, CommittableRecord[K, V]],
    spanNameForElement: SpanNamer[K, V] = SpanNamer.default[K, V],
    enrichLogs: Boolean = true
  ): ZStream[R, Throwable, Spanned[CommittableRecord[K, V]]] =
    stream.mapChunksZIO(_.mapZIO { comm =>
      val traceHeaders = extractTraceHeaders(comm)
      val spanName     = spanNameForElement(comm)

      // Per semconv: consumer "Process" span links to the producer's creation context
      // instead of using it as a parent.
      val producerLink =
        ToHeaders.standard.toContext(traceHeaders).map(ctx => trace4cats.model.Link(ctx.traceId, ctx.spanId))

      tracer.withSpan(name = spanName, kind = SpanKind.Consumer) { span =>
        val addLink = producerLink.fold(ZIO.unit)(span.addLink)

        val record    = comm.record
        val topic     = record.topic
        val partition = record.partition
        val offset    = comm.offset.offset
        val group     = comm.offset.consumerGroupMetadata.map(_.groupId()).getOrElse("Unknown")
        val timestamp = record.timestamp

        val coreAttributes: Map[String, AttributeValue] =
          Map(
            OtelSemconv.MessagingSystem                 -> "kafka",
            OtelSemconv.MessagingOperationType          -> "process",
            OtelSemconv.MessagingOperationName          -> "process",
            OtelSemconv.MessagingDestinationName        -> topic,
            OtelSemconv.MessagingConsumerGroupName      -> group,
            OtelSemconv.MessagingDestinationPartitionId -> partition.toString,
            OtelSemconv.MessagingKafkaOffset            -> offset,
            "messaging.kafka.message.timestamp"         -> timestamp,
            "messaging.kafka.message.timestamp.type"    -> record.timestampType().name
          )

        val enrichedComm = comm.copy(
          commitHandle = _ =>
            // The outer span may be closed so to be safe, we extract the ID and use it to create a sub-span for the commit
            tracer.fromHeaders(
              headers = ToHeaders.standard.fromContext(span.context),
              name = s"commit ${topic}",
              kind = SpanKind.Client
            ) { commitSpan =>
              commitSpan.putAll(coreAttributes) *> comm.offset.commit
            }
        )

        addLink *> span.putAll(coreAttributes) *>
          ZIO.succeed(Spanned(span.extractHeaders(ToHeaders.all), enrichedComm, enrichLogs))
      }
    })

  def tracedConsumeWith[R: Tag, R1: Tag, K, V](
    tracer: ZTracer,
    consumer: Consumer,
    subscription: Subscription,
    keyDeserializer: Deserializer[R, K],
    valueDeserializer: Deserializer[R, V],
    commitRetryPolicy: Schedule[Any, Any, Any] = Schedule.exponential(1.second) && Schedule.recurs(3),
    enrichLogs: Boolean = true
  )(f: KafkaConsumerRecord[K, V] => URIO[R1, Unit]): RIO[R & R1, Unit] =
    consumer.consumeWith[R, R1, K, V](subscription, keyDeserializer, valueDeserializer, commitRetryPolicy) {
      consumerRecord =>
        val traceHeaders = extractConsumerRecordTraceHeaders(consumerRecord)
        val coreAttributes: Map[String, AttributeValue] =
          Map(
            OtelSemconv.MessagingSystem                 -> AttributeValue.StringValue("kafka"),
            OtelSemconv.MessagingOperationType          -> AttributeValue.StringValue("process"),
            OtelSemconv.MessagingOperationName          -> AttributeValue.StringValue("process"),
            OtelSemconv.MessagingDestinationName        -> consumerRecord.topic(),
            OtelSemconv.MessagingDestinationPartitionId -> consumerRecord.partition().toString,
            OtelSemconv.MessagingKafkaOffset            -> consumerRecord.offset()
          )
        val optionals: Map[String, AttributeValue] =
          Option(consumerRecord.key)
            .map(k => OtelSemconv.MessagingKafkaMessageKey -> AttributeValue.StringValue(k.toString))
            .toMap
        val attributes = coreAttributes ++ optionals
        val logAspect =
          if (enrichLogs) ZIOAspect.annotated(attributes.map { case (k, v) => (k, v.toString) }.toSeq*)
          else ZIOAspect.identity

        // Per semconv: consumer "Process" span links to the producer's creation context
        // instead of using it as a parent.
        val producerLink =
          ToHeaders.standard.toContext(traceHeaders).map(ctx => trace4cats.model.Link(ctx.traceId, ctx.spanId))

        tracer.withSpan(name = s"process ${consumerRecord.topic()}", kind = SpanKind.Consumer) { span =>
          val addLink = producerLink.fold(ZIO.unit)(span.addLink)
          addLink *> span.putAll(attributes) *> f(consumerRecord) @@ logAspect
        }
    }
}
