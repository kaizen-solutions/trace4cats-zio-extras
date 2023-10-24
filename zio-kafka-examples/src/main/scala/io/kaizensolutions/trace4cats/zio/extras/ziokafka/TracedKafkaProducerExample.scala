package io.kaizensolutions.trace4cats.zio.extras.ziokafka

import io.kaizensolutions.trace4cats.zio.extras.*
import trace4cats.model.TraceProcess
import zio.*
import zio.kafka.producer.{Producer, ProducerSettings}
import zio.kafka.serde.Serde

object TracedKafkaProducerExample extends ZIOAppDefault {

  override val run: ZIO[ZIOAppArgs & Scope, Any, Any] = {
    val producerSettings = ProducerSettings(List("localhost:9092"))

    ZIO.foreachDiscard(1 to 10)(i =>
      Producer
        .produce("test-topic", s"key-$i", s"value-$i", Serde.string, Serde.string)
        .provide(
          ZLayer.scoped[Any](Producer.make(producerSettings)) >>> KafkaProducerTracer.layer,
          ZLayer.scoped[Any](JaegarEntrypoint.entryPoint(TraceProcess("traced-zio-kafka-producer"))).orDie,
          ZTracer.layer
        )
    )
  }
}
