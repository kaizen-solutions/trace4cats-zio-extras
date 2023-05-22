import cats.data.NonEmptyList
import cats.implicits.toShow
import io.github.embeddedkafka.{EmbeddedKafka, EmbeddedKafkaConfig}
import io.kaizensolutions.trace4cats.zio.extras.ziokafka.{KafkaConsumerTracer, KafkaProducerTracer}
import io.kaizensolutions.trace4cats.zio.extras.{InMemorySpanCompleter, ZTracer}
import trace4cats.model.SpanKind
import zio.*
import zio.kafka.consumer.{Consumer, ConsumerSettings, Subscription}
import zio.kafka.producer.{Producer, ProducerSettings}
import zio.kafka.serde.Serde
import zio.test.*

import java.util.UUID

object ZioKafkaTracedSpec extends ZIOSpecDefault {

  val embeddedKafka: ZLayer[Any, Throwable, EmbeddedKafkaConfig] = ZLayer.scoped(
    ZIO
      .acquireRelease(ZIO.attempt(EmbeddedKafka.start()))(k => ZIO.attempt(k.stop(true)).orDie)
      .map(_.config)
  )

  def producerConfig(config: EmbeddedKafkaConfig): ProducerSettings =
    ProducerSettings(List(s"localhost:${config.kafkaPort}"))

  def consumerConfig(config: EmbeddedKafkaConfig): ConsumerSettings =
    ConsumerSettings(List(s"localhost:${config.kafkaPort}"))
      .withGroupId(UUID.randomUUID().toString)
      .withOffsetRetrieval(Consumer.OffsetRetrieval.Auto(Consumer.AutoOffsetStrategy.Earliest))

  val consumer = ZLayer.scoped(
    ZIO.serviceWith[EmbeddedKafkaConfig](consumerConfig).flatMap(Consumer.make(_))
  )

  val producer = ZLayer.scoped(
    ZIO.serviceWith[EmbeddedKafkaConfig](producerConfig).flatMap(Producer.make)
  )

  def spec = suite("Tracing ZIO Kafka producers and consumers")(
    suite("Producer")(
      test("Produces traces") {
        val topic = UUID.randomUUID().toString
        for {
          _     <- Producer.produce(topic, "key", "value", Serde.string, Serde.string)
          spans <- ZIO.serviceWithZIO[InMemorySpanCompleter](_.retrieveCollected)
        } yield assertTrue(
          spans.exists(span =>
            span.name == "kafka-producer-send-buffer" &&
              span.kind == SpanKind.Producer &&
              span.attributes.exists { case (k, v) =>
                k == "topics" &&
                  v.value.value == NonEmptyList.of(topic)
              }
          ),
          spans.exists(span => span.name == "kafka-producer-broker-ack")
        )
      }
    ),
    suite("Consumer")(
      test("Continues traces from producer") {
        val topic = UUID.randomUUID().toString
        for {
          tracer <- ZIO.service[ZTracer]
          p <- Promise.make[Nothing, Unit]
          _ <- KafkaConsumerTracer
                 .traceConsumerStream(
                   tracer,
                   Consumer
                     .plainStream(Subscription.topics(topic), Serde.string, Serde.string)
                 )
            .debug("element")
                 .tap(_.offset.commit)
                 .tap(_ => p.succeed(()))
                 .runDrain
                 .forkScoped

          _     <- Producer.produce(topic, "key", "value", Serde.string, Serde.string)
          _     <- p.await
          spans <- ZIO.serviceWithZIO[InMemorySpanCompleter](_.retrieveCollected)
          _ <- ZIO.debug(
            spans.map(s => s"name: ${s.name}, id: ${s.context.spanId.show}, parent: ${s.context.parent.map(_.spanId.show)}")
          )
          _ <- ZIO.debug {
            for {
              child <- spans.find(_.name == "kafka-receive")
              parent <- spans.find(_.context.spanId.show == child.context.parent.map(_.spanId.show).getOrElse(""))
            } yield s"child: ${child}, parent: ${parent}"
          }
        } yield assertTrue(
          spans.exists(consumerSpan =>
            consumerSpan.name == "kafka-receive" &&
              spans.exists(parent =>
                parent.kind == SpanKind.Producer &&
                  consumerSpan.context.parent.map(_.spanId.show).contains(parent.context.spanId.show)
              )
            )
        )
      }
    )
  )
    .provideShared(
      producer >>> KafkaProducerTracer.layer,
      consumer,
      embeddedKafka,
      InMemorySpanCompleter.layer("ziokafka"),
      Scope.default
  ) @@ TestAspect.withLiveEnvironment @@ TestAspect.sequential @@ TestAspect.timeout(30.seconds)
}
