package io.kaizensolutions.trace4cats.zio.extras.fs2.kafka.examples

import fs2.kafka.{AutoOffsetReset, ConsumerSettings, KafkaConsumer}
import io.janstenpickle.trace4cats.model.TraceProcess
import io.kaizensolutions.trace4cats.zio.extras.ZTracer
import io.kaizensolutions.trace4cats.zio.extras.fs2.*
import io.kaizensolutions.trace4cats.zio.extras.fs2.kafka.*
import zio.{RIO, Scope, ZIO, ZIOAppArgs, ZIOAppDefault, ZLayer}
import zio.interop.catz.*

import scala.concurrent.duration.*

object TracedKafkaConsumerExample extends ZIOAppDefault {
  type Effect[A] = RIO[ZTracer, A]

  override val run: ZIO[ZIOAppArgs & Scope, Any, Any] = {
    val consumerSettings = ConsumerSettings[Effect, String, String]
      .withBootstrapServers("localhost:9092")
      .withGroupId("example-consumer-group-10")
      .withAutoOffsetReset(AutoOffsetReset.Earliest)

    KafkaConsumer
      .stream(consumerSettings)
      .evalTap(_.subscribeTo("test-topic"))
      .flatMap(_.stream)
      .traceConsumerStream()
      .evalMapTraced("kafka-consumer-print")(e =>
        ZTracer.span(s"${e.record.topic}-${e.record.key}-${e.record.value}")(
          ZIO.succeed(println((e.record.key, e.record.value))).as(e)
        )
      )
      .endTracingEachElement
      .map(_._1)
      .map(_.offset)
      .through(fs2.kafka.commitBatchWithin(10, 10.seconds))
      .compile
      .drain
      .provide(
        ZLayer.scoped[Any](JaegarEntrypoint.entryPoint(TraceProcess("traced-fs2-kafka-consumer"))).orDie,
        ZTracer.layer
      )
  }
}
