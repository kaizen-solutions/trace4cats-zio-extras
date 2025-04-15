---
sidebar_position: 1
title: FS2 Kafka
---

# FS2 Kafka
Trace4Cats ZIO Extras provides tracing for both FS2 Kafka Producers and FS2 Kafka Consumers 

## FS2 Kafka Producer
Kafka records that are produced are augmented with tracing headers. The following example shows how to produce a Kafka record with tracing headers:

```scala mdoc:compile-only
import fs2.Stream
import fs2.kafka.*
import io.kaizensolutions.trace4cats.zio.extras.*
import io.kaizensolutions.trace4cats.zio.extras.fs2.kafka.*
import zio.*
import zio.interop.catz.*

final class TracedKafkaProducer[K, V](
  private val tracer: ZTracer, 
  private val producer: KafkaProducer[Task, K, V]
) {
  val traced: KafkaProducer[Task, K, V] = KafkaProducerTracer.trace(tracer, producer)
}
object TracedKafkaProducer {
  def make[K, V](implicit tagK: Tag[K], tagV: Tag[V]) = {
    val _ = (tagK, tagV)
    for {
      tracer <- ZIO.service[ZTracer]
      producer <- ZIO.service[KafkaProducer[Task, K, V]]
    } yield new TracedKafkaProducer(tracer, producer)
  }

  val layer: URLayer[ZTracer & KafkaProducer[Task, String, String], TracedKafkaProducer[String, String]] =
    ZLayer.fromZIO(make[String, String])
}

val program: RIO[TracedKafkaProducer[String, String], Unit] =
  for {
    tp <- ZIO.service[TracedKafkaProducer[String, String]]
    pipe = KafkaProducer.pipe(tp.traced)
    _ <- Stream.range(1, 100)
          .covary[Task]
          .map(i => ProducerRecords.one(ProducerRecord("test-topic", s"key-$i", s"value-$i")))
          .through(pipe)
          .compile
          .drain
  } yield ()
```

This generates the following for each message produced:
<img width="1155" alt="image" src="https://github.com/kaizen-solutions/trace4cats-zio-extras/assets/14280155/0054e057-5ac2-450e-9858-027352fbec4c"></img>

## FS2 Kafka Consumer
Kafka records that are consumed will automatically continue the trace given that the associated trace header is present 
for the Kafka record. The following example shows how to consume a Kafka record with tracing headers:

## FS2 Kafka `consumeChunk` API

Instead of using streaming traces which can be considered less ergonomic, there is a `tracedConsumeChunk` method
that is similar to the `consumeChunk` method in the `fs2-kafka` library. This method gives you a way to handle
each record individually and takes care of automatically reading the trace headers from the Kafka record. It uses
the `consumeChunk` method under the hood for efficiency.

```scala mdoc:compile-only
import fs2.kafka.{AutoOffsetReset, ConsumerSettings, KafkaConsumer}
import io.kaizensolutions.trace4cats.zio.extras.ZTracer
import io.kaizensolutions.trace4cats.zio.extras.fs2.kafka.*
import zio.*
import zio.interop.catz.*

type Effect[A] = RIO[ZTracer, A]

val consumerSettings = ConsumerSettings[Effect, String, String]
  .withBootstrapServers("localhost:9092")
  .withGroupId("example-consumer-group-10")
  .withAutoOffsetReset(AutoOffsetReset.Earliest)

// Note: This runs indefinitely
val process: Effect[Unit] = KafkaConsumer
  .stream(consumerSettings)
  .subscribeTo("test-topic")
  .tracedConsumeChunk { record =>
    ZTracer.span(s"${record.topic}-${record.key}-${record.value}")(
      ZIO.succeed(println((record.key, record.value)))
    )
  }
```