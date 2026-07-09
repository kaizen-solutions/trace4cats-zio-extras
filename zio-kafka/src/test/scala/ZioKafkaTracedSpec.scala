import cats.data.NonEmptyList
import cats.syntax.show.*
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
              kafkaPort = scala.util.Random.between(64001, 65000),
              controllerPort = scala.util.Random.between(63000, 64000)
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
            span.name.startsWith("send ") &&
              span.kind == SpanKind.Producer &&
              span.attributes.exists { case (k, v) =>
                k == "messaging.destination.name" &&
                v.value.value == NonEmptyList.of(topic)
              }
          ),
          spans.exists(span => span.name.startsWith("send ") && span.kind == SpanKind.Producer)
        )
      }
    ),
    suite("Consumer")(
      test("Links to producer trace context") {
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
          spans <- ZIO.serviceWithZIO[InMemorySpanCompleter](_.awaitCollected(_.exists(_.name == s"commit $topic")))
        } yield assertTrue(
          // Consumer process span links to producer
          spans.exists(consumerSpan =>
            consumerSpan.name == s"process $topic" &&
              consumerSpan.links.exists(links =>
                links.exists(link =>
                  spans.exists(producerSpan =>
                    producerSpan.kind == SpanKind.Producer &&
                      link.traceId.show == producerSpan.context.traceId.show &&
                      link.spanId.show == producerSpan.context.spanId.show
                  )
                )
              )
          ),
          // Commit span exists and is a child of the process span
          spans.exists(commitSpan =>
            commitSpan.name == s"commit $topic" &&
              spans.exists(processSpan =>
                processSpan.name == s"process $topic" &&
                  commitSpan.context.parent.map(_.spanId.show).contains(processSpan.context.spanId.show)
              )
          )
        )
      },
      test("Consume chunks links to producer trace context") {
        val topic = UUID.randomUUID().toString
        for {
          tracer   <- ZIO.service[ZTracer]
          p        <- Promise.make[Nothing, Unit]
          consumer <- ZIO.service[Consumer]
          producer <- ZIO.service[Producer]
          fiber <-
            KafkaConsumerTracer
              .tracedConsumeWith(tracer, consumer, Subscription.topics(topic), Serde.string, Serde.string)(_ =>
                p.succeed(()).unit
              )
              .forkScoped
          _     <- producer.produce(topic, "key", "value", Serde.string, Serde.string)
          _     <- p.await
          _     <- fiber.interrupt
          spans <- ZIO.serviceWithZIO[InMemorySpanCompleter](_.retrieveCollected)
        } yield assertTrue(
          spans.exists(consumerSpan =>
            consumerSpan.name == s"process $topic" &&
              consumerSpan.links.exists(links =>
                links.exists(link =>
                  spans.exists(producerSpan =>
                    producerSpan.kind == SpanKind.Producer &&
                      link.traceId.show == producerSpan.context.traceId.show &&
                      link.spanId.show == producerSpan.context.spanId.show
                  )
                )
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
