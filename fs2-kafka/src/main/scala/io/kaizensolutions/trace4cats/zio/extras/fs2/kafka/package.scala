package io.kaizensolutions.trace4cats.zio.extras.fs2

import fs2.Stream
import fs2.kafka.*
import cats.syntax.all.*
import zio.interop.catz.*
import io.kaizensolutions.trace4cats.zio.extras.ZTracer
import zio.{RIO, ZIO}
import fs2.kafka.consumer.KafkaConsumeChunk.CommitNow

package object kafka {
  implicit class Fs2KafkaTracerConsumerOps[R <: ZTracer, K, V](
    val stream: Stream[RIO[R, *], CommittableConsumerRecord[RIO[R, *], K, V]]
  ) extends AnyVal {
    def traceConsumerStream(
      spanNameForElement: CommittableConsumerRecord[RIO[R, *], K, V] => String =
        (_: CommittableConsumerRecord[RIO[R, *], K, V]) => s"kafka-receive"
    ): TracedStream[R, CommittableConsumerRecord[RIO[R, *], K, V]] =
      Stream
        .eval(ZIO.service[ZTracer])
        .flatMap(tracer => KafkaConsumerTracer.traceConsumerStream(tracer, stream, spanNameForElement))
  }

  implicit class Fs2KafkaConsumerChunksOps[R <: ZTracer, K, V](val consumer: KafkaConsumer[RIO[R, *], K, V])
      extends AnyVal {
    def tracedConsumeChunk(process: ConsumerRecord[K, V] => RIO[R, Any]) =
      ZIO.service[ZTracer].flatMap { tracer =>
        val tracedProcess = KafkaConsumerTracer.processConsumerRecord(tracer, "kafka-consume-chunk")(process)
        consumer.consumeChunk(_.traverse_(tracedProcess).as(CommitNow))
      }
  }

  implicit class Fs2StreamKafkaConsumerChunksOps[R <: ZTracer, K, V](
    val consumer: Stream[RIO[R, *], KafkaConsumer[RIO[R, *], K, V]]
  ) extends AnyVal {
    def tracedConsumeChunk(process: ConsumerRecord[K, V] => RIO[R, Any]) =
      ZIO.service[ZTracer].flatMap { tracer =>
        val tracedProcess = KafkaConsumerTracer.processConsumerRecord(tracer, "kafka-consume-chunk")(process)
        consumer.consumeChunk(_.traverse_(tracedProcess).as(CommitNow))
      }
  }
}
