package io.kaizensolutions.trace4cats.zio.extras

/**
 * OpenTelemetry Semantic Convention attribute keys. See
 * https://opentelemetry.io/docs/specs/semconv/
 */
object OtelSemconv {
  // HTTP (stable)
  val HttpRequestMethod      = "http.request.method"
  val HttpResponseStatusCode = "http.response.status_code"
  val HttpRoute              = "http.route"
  val UrlFull                = "url.full"
  val UrlPath                = "url.path"
  val UrlScheme              = "url.scheme"
  val UrlQuery               = "url.query"
  val ServerAddress          = "server.address"
  val ServerPort             = "server.port"
  val NetworkProtocolVersion = "network.protocol.version"
  val ErrorType              = "error.type"
  val ClientAddress          = "client.address"
  val UserAgentOriginal      = "user_agent.original"

  // Messaging
  val MessagingSystem                 = "messaging.system"
  val MessagingDestinationName        = "messaging.destination.name"
  val MessagingOperationType          = "messaging.operation.type"
  val MessagingOperationName          = "messaging.operation.name"
  val MessagingConsumerGroupName      = "messaging.consumer.group.name"
  val MessagingDestinationPartitionId = "messaging.destination.partition.id"
  val MessagingKafkaMessageKey        = "messaging.kafka.message.key"
  val MessagingKafkaOffset            = "messaging.kafka.offset"
  val MessagingBatchMessageCount      = "messaging.batch.message_count"
  val MessagingMessageId              = "messaging.message.id"
  val MessagingClientId               = "messaging.client.id"

  // Database (stable)
  val DbSystemName         = "db.system.name"
  val DbQueryText          = "db.query.text"
  val DbOperationName      = "db.operation.name"
  val DbNamespace          = "db.namespace"
  val DbCollectionName     = "db.collection.name"
  val DbQuerySummary       = "db.query.summary"
  val DbResponseStatusCode = "db.response.status_code"
  val DbOperationBatchSize = "db.operation.batch.size"

  // GraphQL
  val GraphqlOperationType = "graphql.operation.type"
  val GraphqlOperationName = "graphql.operation.name"
  val GraphqlDocument      = "graphql.document"
}
