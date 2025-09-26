package io.kaizensolutions.trace4cats.zio.extras

import org.apache.kafka.clients.producer.ProducerRecord
import trace4cats.model.{AttributeValue, TraceHeaders}
import zio.kafka.consumer.CommittableRecord
import zio.*

import java.nio.charset.StandardCharsets
import scala.jdk.CollectionConverters.*
import zio.kafka.consumer.*
import zio.kafka.serde.Deserializer
import org.apache.kafka.clients.consumer.ConsumerRecord as KafkaConsumerRecord
import izumi.reflect.Tag

package object ziokafka {
  implicit class ZioKafkaConsumerOps(val consumer: Consumer) extends AnyVal {
    def tracedConsumeWith[R: Tag, R1: Tag, K, V](
      subscription: Subscription,
      keyDeserializer: Deserializer[R, K],
      valueDeserializer: Deserializer[R, V],
      commitRetryPolicy: Schedule[Any, Any, Any] = Schedule.exponential(1.second) && Schedule.recurs(3),
      enrichLogs: Boolean = true
    )(f: KafkaConsumerRecord[K, V] => URIO[R1, Unit]): RIO[R & R1 & ZTracer, Unit] =
      ZIO.serviceWithZIO[ZTracer] { tracer =>
        KafkaConsumerTracer.tracedConsumeWith[R, R1, K, V](
          tracer,
          consumer,
          subscription,
          keyDeserializer,
          valueDeserializer,
          commitRetryPolicy,
          enrichLogs
        )(f)
      }
  }

  def addHeaders[A, B](
    headers: TraceHeaders,
    record: ProducerRecord[A, B]
  ): Task[ProducerRecord[A, B]] =
    ZIO.attempt {
      val mutableHeaders = record.headers()
      headers.values.foreach { case (k, v) =>
        mutableHeaders.add(k.toString, v.getBytes(StandardCharsets.UTF_8))
      }
      record
    }

  def extractTraceHeaders[K, V](in: CommittableRecord[K, V]): TraceHeaders =
    TraceHeaders.of(
      in.record.headers().asScala.map(h => h.key() -> new String(h.value(), StandardCharsets.UTF_8)).toList*
    )

  def extractConsumerRecordTraceHeaders[K, V](in: KafkaConsumerRecord[K, V]): TraceHeaders =
    TraceHeaders.of(
      in.headers().asScala.map(h => h.key() -> new String(h.value(), StandardCharsets.UTF_8)).toList*
    )

  private[ziokafka] implicit def longAttributeValue(value: Long): AttributeValue.LongValue =
    AttributeValue.LongValue(value)

}
