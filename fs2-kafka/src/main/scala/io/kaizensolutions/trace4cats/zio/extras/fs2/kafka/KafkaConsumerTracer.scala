package io.kaizensolutions.trace4cats.zio.extras.fs2.kafka

import cats.syntax.foldable.*
import fs2.Stream
import fs2.kafka.{CommittableConsumerRecord, CommittableOffset, Headers}
import io.janstenpickle.trace4cats.ErrorHandler
import io.janstenpickle.trace4cats.model.{AttributeValue, SpanKind, TraceHeaders}
import io.kaizensolutions.trace4cats.zio.extras.{ZSpan, ZTracer}
import io.kaizensolutions.trace4cats.zio.extras.fs2.*
import zio.{RIO, URIO, ZIO}
import zio.interop.catz.*

object KafkaConsumerTracer {
  def traceConsumerStream[R <: ZTracer, K, V](
    consumerStream: Stream[
      RIO[R, *],
      CommittableConsumerRecord[RIO[R, *], K, V]
    ],
    spanNameForElement: CommittableConsumerRecord[RIO[R, *], K, V] => String =
      (_: CommittableConsumerRecord[RIO[R, *], K, V]) => s"kafka-receive"
  ): TracedStream[R, CommittableConsumerRecord[RIO[R, *], K, V]] =
    consumerStream
      .traceEachElement(spanNameForElement, SpanKind.Consumer, ErrorHandler.empty)(comm =>
        extractTraceHeaders(comm.record.headers)
      )
      .evalMapTraced { comm =>
        val record    = comm.record
        val topic     = record.topic
        val partition = record.partition
        val offset    = record.offset
        val group     = comm.offset.consumerGroupId.getOrElse("")
        val timestamp = record.timestamp

        // Explicit typing to work around lack of contravariance
        val currentSpan: URIO[R, Option[ZSpan]] = ZTracer.getCurrentSpan

        currentSpan.flatMap {
          case Some(span) =>
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
                      // Ensure the same span is tied to the commit effect
                      ZTracer.locally(span)(
                        ZTracer.withSpan(s"${spanNameForElement(comm)}-commit")(span =>
                          span.putAll(coreAttributes) *> comm.offset.commit
                        )
                      )
                  )
                )
              )

          case None =>
            ZIO.succeed(comm)
        }
      }

  private def extractTraceHeaders(in: Headers): TraceHeaders =
    in.toChain.foldMap(header => TraceHeaders.of(header.key() -> header.as[String]))
}
