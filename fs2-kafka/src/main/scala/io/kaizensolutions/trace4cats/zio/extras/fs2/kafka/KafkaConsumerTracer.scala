package io.kaizensolutions.trace4cats.zio.extras.fs2.kafka

import cats.syntax.foldable.*
import fs2.Stream
import fs2.kafka.{CommittableConsumerRecord, CommittableOffset, Headers}
import io.kaizensolutions.trace4cats.zio.extras.fs2.*
import io.kaizensolutions.trace4cats.zio.extras.{ZSpan, ZTracer}
import trace4cats.model.{AttributeValue, SpanKind, TraceHeaders}
import trace4cats.{ErrorHandler, ToHeaders}
import zio.interop.catz.*
import zio.{RIO, URIO}

object KafkaConsumerTracer {
  def traceConsumerStream[R, K, V](
    tracer: ZTracer,
    consumerStream: Stream[
      RIO[R, *],
      CommittableConsumerRecord[RIO[R, *], K, V]
    ],
    spanNameForElement: CommittableConsumerRecord[RIO[R, *], K, V] => String =
      (_: CommittableConsumerRecord[RIO[R, *], K, V]) => s"kafka-receive"
  ): TracedStream[R, CommittableConsumerRecord[RIO[R, *], K, V]] =
    FS2Tracer
      .traceEachElement(tracer, consumerStream, spanNameForElement, SpanKind.Consumer, ErrorHandler.empty)(comm =>
        extractTraceHeaders(comm.record.headers)
      )
      .evalMapWithTracer(tracer, "kafka-consumer") { comm =>
        val record    = comm.record
        val topic     = record.topic
        val partition = record.partition
        val offset    = record.offset
        val group     = comm.offset.consumerGroupId.getOrElse("")
        val timestamp = record.timestamp

        // Explicit typing to work around lack of contravariance
        val currentSpan: URIO[R, ZSpan] = tracer.retrieveCurrentSpan

        currentSpan.flatMap { span =>
          val coreAttributes =
            Map(
              "consumer.group" -> AttributeValue.StringValue(group),
              "topic"          -> AttributeValue.StringValue(topic),
              "partition"      -> AttributeValue.LongValue(partition.toLong),
              "offset"         -> AttributeValue.LongValue(offset)
            )

          val extraAttributes =
            Map(
              "create.time"     -> AttributeValue.LongValue(timestamp.createTime.getOrElse(0L)),
              "log.append.time" -> AttributeValue.LongValue(timestamp.logAppendTime.getOrElse(0L))
            )

          span
            .putAll(coreAttributes ++ extraAttributes)
            .as(
              CommittableConsumerRecord[RIO[R, *], K, V](
                record = record,
                offset = CommittableOffset[RIO[R, *]](
                  topicPartition = comm.offset.topicPartition,
                  offsetAndMetadata = comm.offset.offsetAndMetadata,
                  consumerGroupId = comm.offset.consumerGroupId,
                  commit = _ =>
                    // The outer span may be closed so to be safe, we extract the ID and use it to create a sub-span for the commit
                    // NOTE: If you used batched commits (and you should) - all Kafka element traces won't have a corresponding commit
                    tracer.fromHeaders(
                      headers = ToHeaders.standard.fromContext(span.context),
                      name = s"${spanNameForElement(comm)}-commit",
                      kind = SpanKind.Consumer
                    ) { span =>
                      span.putAll(coreAttributes) *> comm.offset.commit
                    }
                )
              )
            )
        }
      }

  private def extractTraceHeaders(in: Headers): TraceHeaders =
    in.toChain.foldMap(header => TraceHeaders.of(header.key() -> header.as[String]))
}
