package io.kaizensolutions.trace4cats.zio.extras.fs2.kafka.examples

import fs2.kafka.{AutoOffsetReset, ConsumerSettings, KafkaConsumer}
import trace4cats.model.TraceProcess
import io.kaizensolutions.trace4cats.zio.extras.ZTracer
import io.kaizensolutions.trace4cats.zio.extras.fs2.kafka.*
import zio.{RIO, Scope, ZIO, ZIOAppArgs, ZIOAppDefault, ZLayer}
import zio.interop.catz.*

object TracedKafkaConsumerExample extends ZIOAppDefault {
  type Effect[A] = RIO[ZTracer, A]

  override val run: ZIO[ZIOAppArgs & Scope, Any, Any] = {
    val consumerSettings = ConsumerSettings[Effect, String, String]
      .withBootstrapServers("localhost:9092")
      .withGroupId("example-consumer-group-10")
      .withAutoOffsetReset(AutoOffsetReset.Earliest)

    KafkaConsumer
      .stream(consumerSettings)
      .subscribeTo("test-topic")
      .tracedConsumeChunk(e =>
        ZTracer.span(s"${e.topic}-${e.key}-${e.value}")(
          ZIO.succeed(println((e.key, e.value))).as(e)
        )
      )
      .provide(
        ZLayer.scoped[Any](OltpGrpcEntrypoint.entryPoint(TraceProcess("traced-fs2-kafka-consumer"))).orDie,
        ZTracer.layer
      )
  }
}
