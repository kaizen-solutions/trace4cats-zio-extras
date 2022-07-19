package io.kaizensolutions.trace4cats.zio.extras.fs2.kafka.examples

import fs2.kafka.*
import fs2.{Pipe, Stream}
import io.janstenpickle.trace4cats.model.TraceProcess
import io.kaizensolutions.trace4cats.zio.extras.*
import io.kaizensolutions.trace4cats.zio.extras.fs2.kafka.KafkaProducerTracer
import zio.*
import zio.blocking.Blocking
import zio.clock.Clock
import zio.interop.catz.*

object TracedKafkaProducerExample extends App {
  type Effect[A] = RIO[Has[ZTracer] & Clock & Blocking, A]

  val dependencies: URLayer[Clock & Blocking, Has[ZTracer]] =
    ZLayer.fromManaged(JaegarEntrypoint.entryPoint(TraceProcess("traced-fs2-kafka-producer"))).orDie >>> ZTracer.layer

  override def run(args: List[String]): URIO[ZEnv, ExitCode] = {
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
      .exitCode
      .provideCustomLayer(dependencies)
  }
}
