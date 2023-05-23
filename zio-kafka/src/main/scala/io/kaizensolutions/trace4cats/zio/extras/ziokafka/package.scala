package io.kaizensolutions.trace4cats.zio.extras

import org.apache.kafka.clients.producer.ProducerRecord
import trace4cats.model.{AttributeValue, TraceHeaders}
import zio.kafka.consumer.CommittableRecord
import zio.{Task, ZIO}

import java.nio.charset.StandardCharsets
import scala.jdk.CollectionConverters.*

package object ziokafka {
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

  private[ziokafka] implicit def longAttributeValue(value: Long): AttributeValue.LongValue =
    AttributeValue.LongValue(value)

}
