package io.kaizensolutions.trace4cats.zio.extras.sttp4

import io.kaizensolutions.trace4cats.zio.extras.ZTracer
import sttp.capabilities.Effect
import sttp.client4.impl.zio.RIOMonadAsyncError
import sttp.client4.{Backend, GenericRequest, Response, ResponseException}
import sttp.model.{Header, HeaderNames, Headers, StatusCode}
import sttp.monad.MonadError
import trace4cats.ToHeaders
import trace4cats.model.*
import trace4cats.model.AttributeValue.{LongValue, StringValue}
import zio.{Task, ZIOAspect}

// Lifted from io.janstenpickle.trace4cats.sttp.client4 to remain semantically the same
object BackendTracer {
  def apply(
    tracer: ZTracer,
    underlying: Backend[Task],
    toHeaders: ToHeaders = ToHeaders.standard,
    spanNamer: GenericRequest[?, Effect[Task]] => String = methodWithPathSpanNamer,
    dropHeadersWhen: String => Boolean = HeaderNames.isSensitive,
    extractResponseAttributes: Response[?] => Map[String, AttributeValue] = _ => Map.empty,
    enrichLogs: Boolean = true
  ): Backend[Task] = new Backend[Task] {
    override def send[T](request: GenericRequest[T,Any with Effect[Task]]): Task[Response[T]] = {
      val nameOfRequest = spanNamer(request)
      tracer.withSpan(
        name = nameOfRequest,
        kind = SpanKind.Client,
        errorHandler = { case ResponseException.UnexpectedStatusCode(body, responseMetadata) => toSpanStatus(body.toString, responseMetadata.code) }
      ) { span =>
        val traceHeaders = span.extractHeaders(toHeaders)
        val requestWithTraceHeaders =
          request.headers(convertTraceHeaders(traceHeaders).headers*)
        val isSampled = span.context.traceFlags.sampled == SampleDecision.Include

        val reqHeaderAttributes = requestFields(Headers(request.headers), dropHeadersWhen)
        // only extract request attributes if the span is sampled as the host parsing is quite expensive
        val reqExtraAttrs: Map[String, AttributeValue] =
          if (isSampled) toAttributes(request)
          else Map.empty

        val logTraceContext =
          if (enrichLogs) ZIOAspect.annotated(traceHeaders.values.map { case (k, v) => k.toString -> v }.toSeq*)
          else ZIOAspect.identity

        val tracedRequest =
          for {
            _                   <- span.putAll((reqHeaderAttributes ++ reqExtraAttrs)*)
            response            <- underlying.send(requestWithTraceHeaders)
            _                   <- span.setStatus(toSpanStatus(response.statusText, response.code))
            respHeaderAttributes = responseFields(response, dropHeadersWhen)
            // extractResponseAttributes has the potential to be expensive, so only call if the span is sampled
            respExtraAttributes = if (isSampled) extractResponseAttributes(response) else Map.empty
            _                  <- span.putAll((respHeaderAttributes ++ respExtraAttributes)*)
          } yield response

        tracedRequest
          .tapError(e => span.put("error.message", AttributeValue.StringValue(e.getLocalizedMessage)).when(isSampled))
          .tapDefect(cause =>
            span.put("error.cause", AttributeValue.StringValue(cause.prettyPrint)).when(cause.isDie && isSampled)
          ) @@ logTraceContext
      }
    }

    override def close(): Task[Unit] = underlying.close()

    override def monad: MonadError[Task] = new RIOMonadAsyncError[Any]
  }

  def methodWithPathSpanNamer(req: GenericRequest[?, Effect[Task]]): String =
    s"${req.method.method} ${req.uri.path.mkString("/", "/", "")}"

  def convertTraceHeaders(in: TraceHeaders): Headers =
    Headers(in.values.map { case (k, v) => Header(k.toString, v) }.toList)

  private def toSpanStatus(body: String, statusCode: StatusCode): SpanStatus = (body, statusCode) match {
    case (body, StatusCode.BadRequest)           => SpanStatus.Internal(body)
    case (_, StatusCode.Unauthorized)            => SpanStatus.Unauthenticated
    case (_, StatusCode.Forbidden)               => SpanStatus.PermissionDenied
    case (_, StatusCode.NotFound)                => SpanStatus.NotFound
    case (_, StatusCode.TooManyRequests)         => SpanStatus.Unavailable
    case (_, StatusCode.BadGateway)              => SpanStatus.Unavailable
    case (_, StatusCode.ServiceUnavailable)      => SpanStatus.Unavailable
    case (_, StatusCode.GatewayTimeout)          => SpanStatus.Unavailable
    case (_, statusCode) if statusCode.isSuccess => SpanStatus.Ok
    case _                                       => SpanStatus.Unknown
  }

  private val ipv4Regex =
    "^(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$".r

  private val ipv6Regex =
    "^(?:(?:(?:[A-F0-9]{1,4}:){6}|(?=(?:[A-F0-9]{0,4}:){0,6}(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$)(([0-9A-F]{1,4}:){0,5}|:)((:[0-9A-F]{1,4}){1,5}:|:))(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)|(?:[A-F0-9]{1,4}:){7}[A-F0-9]{1,4}|(?=(?:[A-F0-9]{0,4}:){0,7}[A-F0-9]{0,4}$)(([0-9A-F]{1,4}:){1,7}|:)((:[0-9A-F]{1,4}){1,7}|:))$".r

  private def toAttributes[T](req: GenericRequest[?, Effect[Task]]): Map[String, AttributeValue] =
    req.uri.host.map { host =>
      val key = host.toUpperCase match {
        case ipv4Regex(_*) => SemanticAttributeKeys.remoteServiceIpv4
        case ipv6Regex(_*) => SemanticAttributeKeys.remoteServiceIpv6
        case _             => SemanticAttributeKeys.remoteServiceHostname
      }

      key -> StringValue(host)
    }.toMap ++ req.uri.port.map(port => SemanticAttributeKeys.remoteServicePort -> LongValue(port.toLong))

  private def requestFields(
    hs: Headers,
    dropHeadersWhen: String => Boolean
  ): List[(String, AttributeValue)] =
    headerFields(hs, "req", dropHeadersWhen)

  private def responseFields[A](
    res: Response[A],
    dropHeadersWhen: String => Boolean
  ): List[(String, AttributeValue)] =
    headerFields(Headers(res.headers), "resp", dropHeadersWhen) ++
      List(
        "resp.status.code" -> res.code.code
      )

  private def headerFields(
    hs: Headers,
    `type`: String,
    dropHeadersWhen: String => Boolean
  ): List[(String, AttributeValue)] =
    hs.headers
      .filter(h => !dropHeadersWhen(h.name))
      .map { h =>
        (s"${`type`}.header.${h.name}", h.value: AttributeValue)
      }
      .toList
}
