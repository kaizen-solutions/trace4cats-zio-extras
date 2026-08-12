package io.kaizensolutions.trace4cats.zio.extras.fs2.kafka

import cats.data.NonEmptyList
import cats.implicits.toShow
import fs2.kafka.*
import io.github.embeddedkafka.{EmbeddedKafka, EmbeddedKafkaConfig}
import io.kaizensolutions.trace4cats.zio.extras.{InMemorySpanCompleter, SpanRelationship, ZTracer}
import trace4cats.model.SpanKind
import zio.interop.catz.*
import zio.logging.backend.SLF4J
import zio.test.*
import zio.*

import java.util.UUID

object Fs2KafkaTracedSpec extends ZIOSpecDefault {

  override val bootstrap: ZLayer[Any, Any, TestEnvironment] =
    Runtime.removeDefaultLoggers ++ SLF4J.slf4j ++ testEnvironment

  val embeddedKafka: ZLayer[Any, Throwable, EmbeddedKafkaConfig] = ZLayer.scoped(
    ZIO
      .acquireRelease(
        ZIO.attempt(
          EmbeddedKafka.start()(
            EmbeddedKafkaConfig(
              kafkaPort = scala.util.Random.between(54001, 55000),
              controllerPort = scala.util.Random.between(53000, 54000)
            )
          )
        )
      )(k => ZIO.attempt(k.stop(true)).orDie)
      .map(_.config)
  )

  type Producer = KafkaProducer[Task, String, String]
  type Consumer = KafkaConsumer[Task, String, String]

  def producerConfig(config: EmbeddedKafkaConfig) =
    ProducerSettings[Task, String, String]
      .withBootstrapServers(s"localhost:${config.kafkaPort}")

  def consumerConfig(config: EmbeddedKafkaConfig) =
    ConsumerSettings[Task, String, String]
      .withBootstrapServers(s"localhost:${config.kafkaPort}")
      .withGroupId(UUID.randomUUID().toString)
      .withAutoOffsetReset(AutoOffsetReset.Earliest)

  val consumer = ZLayer.scoped(
    for {
      embedded <- ZIO.service[EmbeddedKafkaConfig]
      consumer <- KafkaConsumer.resource(consumerConfig(embedded)).toScopedZIO
    } yield consumer
  )

  val tracedProducer = ZLayer.scoped(
    for {
      ztracer  <- ZIO.service[ZTracer]
      embedded <- ZIO.service[EmbeddedKafkaConfig]
      producer <- KafkaProducer.resource(producerConfig(embedded)).toScopedZIO
    } yield KafkaProducerTracer.trace(ztracer, producer)
  )

  def spec: Spec[Any, Throwable] = suite("Tracing fs2 Kafka producers and consumers")(
    suite("Producer")(
      test("Produces traces") {
        val topic = UUID.randomUUID().toString
        for {
          producer <- ZIO.service[Producer]
          _        <- producer.produceOne(topic, "key", "value").flatten
          spans    <- ZIO.serviceWithZIO[InMemorySpanCompleter](_.retrieveCollected)
        } yield assertTrue(
          spans.exists(span =>
            span.name == s"send $topic" &&
              span.kind == SpanKind.Producer &&
              span.attributes.exists { case (k, v) =>
                k == "messaging.destination.name" &&
                v.value.value == NonEmptyList.of(topic)
              }
          )
        )
      }
    ),
    suite("Consumer")(
      test("ParentChild mode: consumer span is child of producer (same trace ID) and links to producer") {
        val topic = UUID.randomUUID().toString
        ZIO.scoped(
          for {
            tracer   <- ZIO.service[ZTracer]
            producer <- ZIO.service[Producer]
            consumer <- ZIO.service[Consumer]
            p        <- Promise.make[Nothing, Map[String, String]]
            _        <- consumer.subscribeTo(topic)
            _ <- consumer
                   .consumeChunkTraced(tracer, spanRelationship = SpanRelationship.ParentChild)(_ =>
                     p.complete(ZIO.logAnnotations).unit
                   )
                   .forkScoped

            _           <- producer.produceOne(topic, "key", "value")
            annotations <- p.await
            spans       <- ZIO.serviceWithZIO[InMemorySpanCompleter](_.awaitCollected(_.exists(_.name == s"process $topic")))
          } yield assertTrue(
            // Consumer span shares the same trace ID as producer span (parent-child)
            spans.exists(consumerSpan =>
              consumerSpan.name == s"process $topic" &&
                consumerSpan.kind == SpanKind.Consumer &&
                spans.exists(producerSpan =>
                  producerSpan.kind == SpanKind.Producer &&
                    consumerSpan.context.traceId.show == producerSpan.context.traceId.show
                ) &&
                annotations.get("trace_id").contains(consumerSpan.context.traceId.show) &&
                annotations.get("span_id").contains(consumerSpan.context.spanId.show)
            ),
            // Link is still added
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
        )
      },
      test("Link mode: consumer span has different trace ID and links to producer") {
        val topic = UUID.randomUUID().toString
        ZIO.scoped(
          for {
            tracer   <- ZIO.service[ZTracer]
            producer <- ZIO.service[Producer]
            consumer <- ZIO.service[Consumer]
            p        <- Promise.make[Nothing, Unit]
            _        <- consumer.subscribeTo(topic)
            _ <- consumer
                   .consumeChunkTraced(tracer, spanRelationship = SpanRelationship.Link)(_ => p.succeed(()).unit)
                   .forkScoped

            _     <- producer.produceOne(topic, "key", "value")
            _     <- p.await
            spans <- ZIO.serviceWithZIO[InMemorySpanCompleter](_.awaitCollected(_.exists(_.name == s"process $topic")))
          } yield assertTrue(
            // Consumer span has a different trace ID than producer span
            spans.exists(consumerSpan =>
              consumerSpan.name == s"process $topic" &&
                consumerSpan.kind == SpanKind.Consumer &&
                spans.exists(producerSpan =>
                  producerSpan.kind == SpanKind.Producer &&
                    consumerSpan.context.traceId.show != producerSpan.context.traceId.show
                )
            ),
            // Link is still added
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
        )
      },
      test("Experiences no lag after processing all elements") {
        val topic = UUID.randomUUID().toString
        ZIO.scoped(
          for {
            tracer       <- ZIO.service[ZTracer]
            producer     <- ZIO.service[Producer]
            consumer     <- ZIO.service[Consumer]
            counter      <- Ref.make(0)
            totalElements = 1000
            p            <- Promise.make[Nothing, Unit]
            _            <- consumer.subscribeTo(topic)
            _ <- consumer
                   .consumeChunkTraced(tracer)(_ =>
                     counter.updateAndGet(_ + 1).flatMap {
                       case i if i == totalElements - 1 => p.succeed(()).unit
                       case _                           => ZIO.unit
                     }
                   )
                   .forkScoped

            _           <- ZIO.foreachParDiscard(1 to totalElements)(i => producer.produceOne(topic, "key", s"value $i"))
            _           <- p.await
            _           <- ZIO.sleep(1.second)
            assignedTps <- consumer.assignment
            endOffsets  <- consumer.endOffsets(assignedTps)
            lags <- ZIO.foreach(endOffsets) { case (tp, end) =>
                      consumer.position(tp).map { current =>
                        tp -> (end - current)
                      }
                    }
          } yield assertTrue(
            lags.forall { case (_, l) => l == 0 }
          )
        )
      }
    )
  )
    .provideShared(
      tracedProducer,
      consumer,
      embeddedKafka,
      InMemorySpanCompleter.layer("fs2kafka")
    ) @@ TestAspect.withLiveEnvironment @@ TestAspect.sequential
}
