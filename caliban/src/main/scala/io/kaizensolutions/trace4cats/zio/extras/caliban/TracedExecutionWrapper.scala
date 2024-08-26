package io.kaizensolutions.trace4cats.zio.extras.caliban

import io.kaizensolutions.trace4cats.zio.extras.ZTracer
import caliban.wrappers.Wrapper.ExecutionWrapper
import caliban.*
import caliban.execution.*
import caliban.InputValue.ObjectValue
import caliban.Value.FloatValue.FloatNumber
import caliban.Value.IntValue.IntNumber
import caliban.Value.StringValue
import caliban.parsing.adt.OperationType
import caliban.tools.stitching.RemoteQuery
import zio.*
import trace4cats.model.AttributeValue

final class TracedExecutionWrapper(tracer: ZTracer) {
  import TracedExecutionWrapper.*

  val wrapper: ExecutionWrapper[Any] = new ExecutionWrapper[Any] {

    override def wrap[R <: Any](
      f: ExecutionRequest => ZIO[R, Nothing, GraphQLResponse[CalibanError]]
    ): ExecutionRequest => ZIO[R, Nothing, GraphQLResponse[CalibanError]] = { request =>
      val parentField = request.field.fields.head.name
      // skip introspection queries
      if (parentField == "__schema") f(request)
      else {
        tracer.withSpan(spanName(request)) { span =>
          span.putAll(attributes(request.field)) *>
            f(request)
        }
      }
    }
  }
}
object TracedExecutionWrapper {
  val wrapper: ExecutionWrapper[ZTracer] = new ExecutionWrapper[ZTracer] {
    override def wrap[R <: ZTracer](
      f: ExecutionRequest => ZIO[R, Nothing, GraphQLResponse[CalibanError]]
    ): ExecutionRequest => ZIO[R, Nothing, GraphQLResponse[CalibanError]] = { request =>
      val parentField = request.field.fields.head.name
      // skip introspection queries
      if (parentField == "__schema") f(request)
      else {
        ZTracer.withSpan(spanName(request)) { span =>
          span.putAll(attributes(request.field)) *> f(request)
        }
      }
    }
  }

  private def spanName(request: ExecutionRequest): String = {
    val operationTypeString = request.operationType match {
      case OperationType.Query        => "query"
      case OperationType.Mutation     => "mutation"
      case OperationType.Subscription => "subscription"
    }

    // The span name MUST be of the format <graphql.operation.type> <graphql.operation.name>
    // provided that graphql.operation.type and graphql.operation.name are available. If
    // graphql.operation.name is not available, the span SHOULD be named <graphql.operation.type>.
    // When <graphql.operation.type> is not available, GraphQL Operation MAY be used as span name.
    Seq(Option(operationTypeString), request.operationName).flatten.mkString(" ")
  }

  private def attributes[T, R](
    field: Field
  ) = Map("document" -> AttributeValue.StringValue(graphQLQuery(field)))

  private def graphQLQuery(field: Field): String =
    RemoteQuery.apply(maskField(field)).toGraphQLRequest.query.getOrElse("")

  private def maskArguments(args: Map[String, InputValue]): Map[String, InputValue] =
    args.map { case (k, v) =>
      val v1 = v match {
        case _: ObjectValue      => ObjectValue(Map.empty)
        case _: StringValue      => StringValue("")
        case _: Value.IntValue   => IntNumber(0)
        case _: Value.FloatValue => FloatNumber(0f)
        case x                   => x
      }
      (k, v1)
    }.toMap

  private def maskField(f: Field): Field =
    f.copy(
      arguments = maskArguments(f.arguments),
      fields = f.fields.map(maskField)
    )
}
