package io.kaizensolutions.trace4cats.zio.extras.fs2.kafka.examples

import fs2.{Pipe, Stream}
import fs2.kafka.{KafkaProducer, ProducerRecord, ProducerRecords, ProducerResult, ProducerSettings}
import io.kaizensolutions.trace4cats.zio.extras.*
import zio.*
import zio.interop.catz.*
import trace4cats.model.TraceProcess
import io.kaizensolutions.trace4cats.zio.extras.fs2.kafka.KafkaProducerTracer

object TracedKafkaProducerExample extends ZIOAppDefault {
  type Effect[A] = RIO[ZTracer, A]

  override val run: ZIO[ZIOAppArgs & Scope, Any, Any] = {
    val producerSettings = ProducerSettings[Effect, String, String]
      .withBootstrapServers("localhost:9092")

    def producerPipe(
      tracer: ZTracer
    ): Stream[Effect, Pipe[Effect, ProducerRecords[Unit, String, String], ProducerResult[Unit, String, String]]] =
      KafkaProducer
        .stream[Effect, String, String](producerSettings)
        .map(underlying => KafkaProducerTracer.trace(tracer, underlying))
        .map(producer => KafkaProducer.pipe[Effect, String, String, Unit](producerSettings, producer))

    ZIO
      .service[ZTracer]
      .flatMap(tracer =>
        tracer.span("kafka-producer-example") {
          producerPipe(tracer)
            .flatMap(producerPipe =>
              Stream
                .range(1, 100)
                .covary[Effect]
                .map(i => ProducerRecords.one(ProducerRecord("test-topic", s"key-$i", s"value-$i")))
                .through(producerPipe)
            )
            .compile
            .drain
        }
      )
      .provide(
        ZLayer.scoped[Any](JaegarEntrypoint.entryPoint(TraceProcess("traced-fs2-kafka-producer"))).orDie,
        ZTracer.layer
      )
  }
}
