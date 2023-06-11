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

```scala mdoc:compile-only
import fs2.Stream
import fs2.kafka.*
import io.kaizensolutions.trace4cats.zio.extras.*
import io.kaizensolutions.trace4cats.zio.extras.fs2.*
import io.kaizensolutions.trace4cats.zio.extras.fs2.kafka.*
import zio.{durationInt => _, *}
import zio.interop.catz.*

import scala.concurrent.duration.*

type Effect[A] = RIO[ZTracer, A]

val consumerSettings = ConsumerSettings[Effect, String, String]
  .withBootstrapServers("localhost:9092")
  .withGroupId("example-consumer-group-10")
  .withAutoOffsetReset(AutoOffsetReset.Earliest)

val consumerStream: Stream[Effect, Unit] = 
  KafkaConsumer.stream(consumerSettings)
  .evalTap(_.subscribeTo("test-topic"))
  .flatMap(_.stream)
  .traceConsumerStream()
  .evalMapTraced("kafka-consumer-print")(e =>
    ZTracer.span(s"${e.record.topic}-${e.record.key}-${e.record.value}")(
      ZIO.succeed(println((e.record.key, e.record.value))).as(e)
    )
  )
  .endTracingEachElement
  .map(_.offset)
  .through(commitBatchWithin(10, 10.seconds))
```

This produces the following traces:
<img width="801" alt="image" src="https://github.com/kaizen-solutions/trace4cats-zio-extras/assets/14280155/377fa2d3-d1fc-4b6e-8508-857f855e97c9"></img>

Notice how the trace is continued for each Kafka record that is consumed and Jaegar is able to show the full trace 
(from the producer all the way to the consumer).