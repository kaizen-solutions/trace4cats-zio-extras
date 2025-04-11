package io.kaizensolutions.trace4cats.zio.extras.fs2

import cats.syntax.all.*
import fs2.Stream
import fs2.kafka.*
import fs2.kafka.consumer.KafkaConsumeChunk.CommitNow
import io.kaizensolutions.trace4cats.zio.extras.ZTracer
import zio.interop.catz.*
import zio.{RIO, ZIO}

package object kafka {
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

  implicit class Fs2KafkaConsumerOps[R, K, V](val self: KafkaConsumer[RIO[R, *], K, V]) extends AnyVal {
    def consumeChunkTraced(ztracer: ZTracer, spanName: String)(
      process: ConsumerRecord[K, V] => RIO[R, Unit]
    ): RIO[R, Nothing] = {
      val tracedProcess = KafkaConsumerTracer.processConsumerRecord(ztracer, spanName)(process)
      self.consumeChunk(_.traverse(tracedProcess).as(CommitNow))
    }
  }
}
