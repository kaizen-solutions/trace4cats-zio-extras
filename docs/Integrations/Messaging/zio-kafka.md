---
sidebar_position: 2
title: ZIO Kafka
---

# ZIO Kafka
Trace4Cats ZIO Extras provides tracing for both ZIO Kafka Producers and ZIO Kafka Consumers 

## ZIO Kafka Producer
Kafka records that are produced are augmented with tracing headers. The following example shows how to produce a Kafka record with tracing headers:

```scala mdoc:compile-only
import io.kaizensolutions.trace4cats.zio.extras.*
import io.kaizensolutions.trace4cats.zio.extras.ziokafka.*
import zio.*
import zio.kafka.producer.*
import zio.kafka.serde.*

val producerSettings = ProducerSettings(List("localhost:9092"))
val tracedProducerLayer = ZLayer.scoped[Any](Producer.make(producerSettings)) >>> KafkaProducerTracer.layer

val program: ZIO[ZTracer, Throwable, Unit] = 
  ZIO.foreachDiscard(1 to 10)(i =>
  ZIO.serviceWithZIO[Producer](_.produce("test-topic", s"key-$i", s"value-$i", Serde.string, Serde.string))
    .provideSome[ZTracer](tracedProducerLayer)
)
```

Here is an example of the trace generated for one of the messages produced:
<img width="1118" alt="image" src="https://github.com/kaizen-solutions/trace4cats-zio-extras/assets/14280155/55555c37-09e3-4a3a-bc53-b46004b471e9"></img>


## ZIO Kafka Consumer
Kafka records that are consumed will automatically continue the trace given that the associated trace header is present 
for the Kafka record. The following example shows how to consume a Kafka record with tracing headers:

```scala mdoc:compile-only
import io.kaizensolutions.trace4cats.zio.extras.*
import io.kaizensolutions.trace4cats.zio.extras.ziokafka.*
import zio.kafka.consumer.*
import zio.kafka.serde.*
import zio.*
import zio.stream.*

val consumerSettings = ConsumerSettings(List(s"localhost:9092"))
  .withGroupId("example-traced-group")
  .withOffsetRetrieval(Consumer.OffsetRetrieval.Auto(Consumer.AutoOffsetStrategy.Earliest))

val consume: ZIO[ZTracer & Consumer, Throwable, Unit] = 
  ZIO.serviceWithZIO[ZTracer](tracer =>
    KafkaConsumerTracer
      .traceConsumerStream(
        tracer,
        ZStream.serviceWithStream[Consumer](_.plainStream(Subscription.topics("test-topic"), Serde.string, Serde.string))
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
```

Here is an example of the trace generated for one of the messages consumed:
<img width="1120" alt="image" src="https://github.com/kaizen-solutions/trace4cats-zio-extras/assets/14280155/255a5ab8-13d1-49b2-9b5b-14a1216a5cd7"></img>
