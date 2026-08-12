package io.kaizensolutions.trace4cats.zio.extras

import trace4cats.model.AttributeValue
import zio.LogAnnotation

/**
 * Controls which span attributes from Kafka consumer tracing are surfaced as
 * ZIO log annotations. The default includes only the attributes useful for
 * correlating logs to a specific message: topic, partition, offset, and key.
 */
trait KafkaLogAnnotations {
  def apply(attributes: Map[String, AttributeValue]): Set[LogAnnotation]
}

object KafkaLogAnnotations {

  /** Logs topic, partition, offset, and message key. */
  val default: KafkaLogAnnotations = (attributes: Map[String, AttributeValue]) => {
    val keys = Set(
      OtelSemconv.MessagingDestinationName,
      OtelSemconv.MessagingDestinationPartitionId,
      OtelSemconv.MessagingKafkaOffset,
      OtelSemconv.MessagingKafkaMessageKey
    )
    attributes.collect {
      case (k, v) if keys.contains(k) => LogAnnotation(k, v.toString)
    }.toSet
  }

  /** Logs all span attributes as log annotations. */
  val all: KafkaLogAnnotations = (attributes: Map[String, AttributeValue]) =>
    attributes.map { case (k, v) => LogAnnotation(k, v.toString) }.toSet

  /** No log annotations from span attributes. */
  val none: KafkaLogAnnotations = _ => Set.empty
}
