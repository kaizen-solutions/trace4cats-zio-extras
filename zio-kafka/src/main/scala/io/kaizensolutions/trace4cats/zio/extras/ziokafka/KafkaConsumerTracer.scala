package io.kaizensolutions.trace4cats.zio.extras.ziokafka

import io.kaizensolutions.trace4cats.zio.extras.*
import trace4cats.model.AttributeValue
import trace4cats.{SpanKind, ToHeaders}
import zio.kafka.consumer.CommittableRecord
import zio.stream.ZStream

object KafkaConsumerTracer {
  type SpanNamer[K, V] = CommittableRecord[K, V] => String
  object SpanNamer {
    def default[K, V]: SpanNamer[K, V] = _ => s"kafka-receive"
  }

  def traceConsumerStream[R, K, V](
    tracer: ZTracer,
    stream: ZStream[R, Throwable, CommittableRecord[K, V]],
    spanNameForElement: SpanNamer[K, V] = SpanNamer.default[K, V],
    enrichLogs: Boolean = true
  ): ZStream[R, Throwable, Spanned[CommittableRecord[K, V]]] =
    tracer
      .traceEachElement(extractTraceHeaders[K, V], spanNameForElement, SpanKind.Consumer, enrichLogs = enrichLogs)(
        stream
      )
      .mapZIOWithTracer(tracer, "kafka-consumer") { comm =>
        tracer.retrieveCurrentSpan.flatMap { span =>
          // Now, enrich the span with the core attributes of the committable record
          val record    = comm.record
          val topic     = record.topic
          val partition = record.partition
          val offset    = comm.offset.offset
          val group     = comm.offset.consumerGroupMetadata.map(_.groupId()).getOrElse("Unknown")
          val timestamp = record.timestamp

          val coreAttributes: Map[String, AttributeValue] =
            Map(
              "consumer.group" -> group,
              "topic"          -> topic,
              "partition"      -> partition,
              "offset"         -> offset,
              "timestamp"      -> timestamp,
              "timestamp.type" -> record.timestampType().name
            )

          span
            .putAll(coreAttributes)
            .as(
              comm.copy(
                commitHandle = _ =>
                  // The outer span may be closed so to be safe, we extract the ID and use it to create a sub-span for the commit
                  // NOTE: If you used batched commits (and you should) - all Kafka element traces won't have a corresponding commit
                  tracer.fromHeaders(
                    headers = ToHeaders.standard.fromContext(span.context),
                    name = s"${spanNameForElement(comm)}-commit",
                    kind = SpanKind.Consumer
                  ) { span =>
                    span.putAll(coreAttributes) *> comm.offset.commit
                  // this should be fine, since the commit applies to the same topicPartition as the original record
                  }
              )
            )
        }
      }
}
