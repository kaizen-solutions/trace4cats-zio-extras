package io.kaizensolutions.trace4cats.zio.extras.fs2.kafka.examples

import fs2.kafka.{AutoOffsetReset, ConsumerSettings, KafkaConsumer}
import io.janstenpickle.trace4cats.model.TraceProcess
import io.kaizensolutions.trace4cats.zio.extras.ZTracer
import io.kaizensolutions.trace4cats.zio.extras.fs2.*
import io.kaizensolutions.trace4cats.zio.extras.fs2.kafka.*
import zio.*
import zio.blocking.Blocking
import zio.clock.Clock
import zio.interop.catz.*

import scala.concurrent.duration.*

object TracedKafkaConsumerExample extends App {
  type Effect[A] = RIO[Clock & Blocking & Has[ZTracer], A]

  val dependencies: URLayer[Clock & Blocking, Has[ZTracer]] =
    ZLayer.fromManaged(JaegarEntrypoint.entryPoint(TraceProcess("traced-fs2-kafka-consumer"))).orDie >>> ZTracer.layer

  override def run(args: List[String]): URIO[ZEnv, ExitCode] = {
    val consumerSettings = ConsumerSettings[Effect, String, String]
      .withBootstrapServers("localhost:9092")
      .withGroupId("example-consumer-group-100")
      .withAutoOffsetReset(AutoOffsetReset.Earliest)

    KafkaConsumer
      .stream(consumerSettings)
      .evalTap(_.subscribeTo("test-topic"))
      .flatMap(_.stream)
      .traceConsumerStream()
      .evalMapTraced(e =>
        ZTracer.span(s"${e.record.topic}-${e.record.key}-${e.record.value}")(
          ZIO.succeed(println((e.record.key, e.record.value))).as(e)
        )
      )
      .endTracingEachElement()
      .map(_._1)
      .map(_.offset)
      .through(fs2.kafka.commitBatchWithin(10, 10.seconds))
      .compile
      .drain
      .exitCode
      .provideCustomLayer(dependencies)
  }
}
