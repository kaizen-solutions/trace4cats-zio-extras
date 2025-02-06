package io.kaizensolutions.trace4cats.zio.extras.fs2.kafka.examples

import fs2.kafka.{AutoOffsetReset, ConsumerSettings, KafkaConsumer}
import trace4cats.model.TraceProcess
import io.kaizensolutions.trace4cats.zio.extras.ZTracer
import io.kaizensolutions.trace4cats.zio.extras.fs2.kafka.*
import zio.{RIO, Scope, ZIO, ZIOAppArgs, ZIOAppDefault, ZLayer}
import zio.interop.catz.*

object TracedKafkaConsumerChunksExample extends ZIOAppDefault {
  type Effect[A] = RIO[ZTracer, A]

  override val run: ZIO[ZIOAppArgs & Scope, Any, Any] = {
    val consumerSettings = ConsumerSettings[Effect, String, String]
      .withBootstrapServers("localhost:9092")
      .withGroupId("example-consumer-group-10")
      .withAutoOffsetReset(AutoOffsetReset.Earliest)

    KafkaConsumer
      .stream(consumerSettings)
      .subscribeTo("test-topic")
      .tracedConsumeChunk { record =>
        ZTracer.span(s"${record.topic}-${record.key}-${record.value}")(
          ZIO.succeed(println((record.key, record.value)))
        )
      }
      .provide(
        ZLayer.scoped[Any](OltpGrpcEntrypoint.entryPoint(TraceProcess("traced-fs2-kafka-consumer"))).orDie,
        ZTracer.layer
      )
  }
}
