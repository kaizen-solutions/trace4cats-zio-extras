package io.kaizensolutions.trace4cats.zio.extras.fs2

import fs2.Stream
import fs2.kafka.CommittableConsumerRecord
import io.kaizensolutions.trace4cats.zio.extras.ZTracer
import zio.{RIO, ZIO}

package object kafka {
  implicit class Fs2KafkaTracerConsumerOps[R <: ZTracer, K, V](
    val stream: Stream[RIO[R, *], CommittableConsumerRecord[RIO[R, *], K, V]]
  ) {
    def traceConsumerStream(
      spanNameForElement: CommittableConsumerRecord[RIO[R, *], K, V] => String =
        (_: CommittableConsumerRecord[RIO[R, *], K, V]) => s"kafka-receive"
    ): TracedStream[R, CommittableConsumerRecord[RIO[R, *], K, V]] =
      Stream
        .eval(ZIO.service[ZTracer])
        .flatMap(tracer => KafkaConsumerTracer.traceConsumerStream(tracer, stream, spanNameForElement))
  }
}
