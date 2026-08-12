package io.kaizensolutions.trace4cats.zio.extras.ziokafka

import io.kaizensolutions.trace4cats.zio.extras.*
import izumi.reflect.Tag
import org.apache.kafka.clients.consumer.ConsumerRecord as KafkaConsumerRecord
import trace4cats.model.AttributeValue
import trace4cats.{SpanKind, ToHeaders}
import zio.*
import zio.kafka.consumer.*
import zio.kafka.serde.Deserializer
import zio.stream.ZStream

object KafkaConsumerTracer {
  type SpanNamer[K, V] = CommittableRecord[K, V] => String
  object SpanNamer {
    def default[K, V]: SpanNamer[K, V] = record => s"process ${record.record.topic}"
  }

  def traceConsumerStream[R, K, V](
    tracer: ZTracer,
    stream: ZStream[R, Throwable, CommittableRecord[K, V]],
    spanNameForElement: SpanNamer[K, V] = SpanNamer.default[K, V],
    kafkaLogAnnotations: KafkaLogAnnotations = KafkaLogAnnotations.default,
    spanRelationship: SpanRelationship = SpanRelationship.ParentChild
  ): ZStream[R, Throwable, Spanned[CommittableRecord[K, V]]] =
    stream.mapChunksZIO(_.mapZIO { comm =>
      val traceHeaders = extractTraceHeaders(comm)
      val spanName     = spanNameForElement(comm)
      val record       = comm.record
      val topic        = record.topic

      val attributes = coreAttributes(topic, record.partition, comm.offset.offset, Option(record.key).map(_.toString))

      withConsumerSpan(tracer, traceHeaders, spanName, spanRelationship, attributes, kafkaLogAnnotations) { span =>
        val enrichedComm = comm.copy(
          commitHandle = _ =>
            tracer.fromHeaders(
              headers = ToHeaders.standard.fromContext(span.context),
              name = s"commit $topic",
              kind = SpanKind.Client
            ) { commitSpan =>
              commitSpan.putAll(attributes) *> comm.offset.commit
            }
        )
        ZIO.succeed(Spanned(span.extractHeaders(ToHeaders.all), enrichedComm))
      }
    })

  def tracedConsumeWith[R: Tag, R1: Tag, K, V](
    tracer: ZTracer,
    consumer: Consumer,
    subscription: Subscription,
    keyDeserializer: Deserializer[R, K],
    valueDeserializer: Deserializer[R, V],
    commitRetryPolicy: Schedule[Any, Any, Any] = Schedule.exponential(1.second) && Schedule.recurs(3),
    kafkaLogAnnotations: KafkaLogAnnotations = KafkaLogAnnotations.default,
    spanRelationship: SpanRelationship = SpanRelationship.ParentChild
  )(f: KafkaConsumerRecord[K, V] => URIO[R1, Unit]): RIO[R & R1, Unit] =
    consumer.consumeWith[R, R1, K, V](subscription, keyDeserializer, valueDeserializer, commitRetryPolicy) {
      consumerRecord =>
        val traceHeaders = extractConsumerRecordTraceHeaders(consumerRecord)
        val spanName     = s"process ${consumerRecord.topic()}"
        val attributes = coreAttributes(
          consumerRecord.topic(),
          consumerRecord.partition(),
          consumerRecord.offset(),
          Option(consumerRecord.key).map(_.toString)
        )

        withConsumerSpan(tracer, traceHeaders, spanName, spanRelationship, attributes, kafkaLogAnnotations) { _ =>
          f(consumerRecord)
        }
    }

  private def coreAttributes(
    topic: String,
    partition: Int,
    offset: Long,
    key: Option[String]
  ): Map[String, AttributeValue] = {
    val base: Map[String, AttributeValue] = Map(
      OtelSemconv.MessagingSystem                 -> AttributeValue.StringValue("kafka"),
      OtelSemconv.MessagingOperationType          -> AttributeValue.StringValue("process"),
      OtelSemconv.MessagingOperationName          -> AttributeValue.StringValue("process"),
      OtelSemconv.MessagingDestinationName        -> AttributeValue.StringValue(topic),
      OtelSemconv.MessagingDestinationPartitionId -> AttributeValue.StringValue(partition.toString),
      OtelSemconv.MessagingKafkaOffset            -> AttributeValue.LongValue(offset)
    )
    key.fold(base)(k => base + (OtelSemconv.MessagingKafkaMessageKey -> AttributeValue.StringValue(k)))
  }

  private def withConsumerSpan[R, A](
    tracer: ZTracer,
    traceHeaders: trace4cats.model.TraceHeaders,
    spanName: String,
    spanRelationship: SpanRelationship,
    attributes: Map[String, AttributeValue],
    kafkaLogAnnotations: KafkaLogAnnotations
  )(body: ZSpan => ZIO[R, Nothing, A]): ZIO[R, Nothing, A] = {
    val producerLink =
      ToHeaders.standard.toContext(traceHeaders).map(ctx => trace4cats.model.Link(ctx.traceId, ctx.spanId))

    def run(span: ZSpan): ZIO[R, Nothing, A] = {
      val addLink = producerLink.fold(ZIO.unit)(span.addLink)
      addLink *> span.putAll(attributes) *>
        ZIO.logAnnotate(kafkaLogAnnotations(attributes))(
          body(span)
        )
    }

    spanRelationship match {
      case SpanRelationship.ParentChild =>
        tracer.fromHeaders(headers = traceHeaders, name = spanName, kind = SpanKind.Consumer)(run)
      case SpanRelationship.Link =>
        tracer.withSpan(name = spanName, kind = SpanKind.Consumer)(run)
    }
  }
}
