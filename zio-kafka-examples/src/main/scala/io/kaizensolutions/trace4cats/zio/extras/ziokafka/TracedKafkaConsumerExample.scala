package io.kaizensolutions.trace4cats.zio.extras.ziokafka

import io.kaizensolutions.trace4cats.zio.extras.*
import trace4cats.model.TraceProcess
import zio.kafka.consumer.{Consumer, ConsumerSettings, Subscription}
import zio.kafka.serde.Serde
import zio.{ZIO, ZIOAppDefault, ZLayer}

import java.util.UUID

object TracedKafkaConsumerExample extends ZIOAppDefault {
  override val run = {
    val consumerSettings = ConsumerSettings(List(s"localhost:9092"))
      .withGroupId(UUID.randomUUID().toString)
      .withOffsetRetrieval(Consumer.OffsetRetrieval.Auto(Consumer.AutoOffsetStrategy.Earliest))

    ZIO
      .serviceWithZIO[ZTracer](tracer =>
        KafkaConsumerTracer
          .traceConsumerStream(
            tracer,
            Consumer
              .plainStream(Subscription.topics("test-topic"), Serde.string, Serde.string)
          )
          .tapWithTracer(tracer, "internal") { record =>
            val event = s"${record.record.topic}-${record.record.key}-${record.record.value}"
            ZIO.log(s"handled an event $event")
          }
          .endTracingEachElement
          .map(_.offset)
          .aggregateAsync(Consumer.offsetBatches)
          .tap(_.commit)
          .runDrain
      )
      .provide(
        ZLayer.scoped[Any](Consumer.make(consumerSettings)),
        ZLayer.scoped[Any](JaegarEntrypoint.entryPoint(TraceProcess("traced-zio-kafka-consumer"))),
        ZTracer.layer
      )
  }
}
