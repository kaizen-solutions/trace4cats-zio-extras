import cats.data.NonEmptyList
import cats.implicits.toShow
import io.github.embeddedkafka.{EmbeddedKafka, EmbeddedKafkaConfig}
import io.kaizensolutions.trace4cats.zio.extras.*
import io.kaizensolutions.trace4cats.zio.extras.ziokafka.{KafkaConsumerTracer, KafkaProducerTracer}
import trace4cats.model.SpanKind
import zio.*
import zio.kafka.consumer.{Consumer, ConsumerSettings, Subscription}
import zio.kafka.producer.{Producer, ProducerSettings}
import zio.kafka.serde.Serde
import zio.logging.backend.SLF4J
import zio.test.*

import java.util.UUID

object ZioKafkaTracedSpec extends ZIOSpecDefault {

  override val bootstrap: ZLayer[Any, Any, TestEnvironment] =
    Runtime.removeDefaultLoggers ++ SLF4J.slf4j ++ testEnvironment

  val embeddedKafka: ZLayer[Any, Throwable, EmbeddedKafkaConfig] = ZLayer.scoped(
    ZIO
      .acquireRelease(
        ZIO.attempt(
          EmbeddedKafka.start()(
            EmbeddedKafkaConfig(
              kafkaPort = scala.util.Random.between(1000, 65000),
              zooKeeperPort = scala.util.Random.between(1000, 65000)
            )
          )
        )
      )(k => ZIO.attempt(k.stop(true)).orDie)
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

  def spec: Spec[Any, Throwable] = suite("Tracing ZIO Kafka producers and consumers")(
    suite("Producer")(
      test("Produces traces") {
        val topic = UUID.randomUUID().toString
        for {
          _     <- ZIO.serviceWithZIO[Producer](_.produce(topic, "key", "value", Serde.string, Serde.string))
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
          tracer   <- ZIO.service[ZTracer]
          p        <- Promise.make[Nothing, Unit]
          consumer <- ZIO.service[Consumer]
          producer <- ZIO.service[Producer]
          _ <- KafkaConsumerTracer
                 .traceConsumerStream(
                   tracer,
                   consumer.plainStream(Subscription.topics(topic), Serde.string, Serde.string)
                 )
                 .endTracingEachElement
                 .tap(_.offset.commit)
                 .tap(_ => p.succeed(()))
                 .runDrain
                 .forkScoped

          _     <- producer.produce(topic, "key", "value", Serde.string, Serde.string)
          _     <- p.await
          spans <- ZIO.serviceWithZIO[InMemorySpanCompleter](_.retrieveCollected)
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
