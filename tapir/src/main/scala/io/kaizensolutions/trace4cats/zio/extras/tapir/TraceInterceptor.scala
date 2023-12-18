package io.kaizensolutions.trace4cats.zio.extras.tapir

import io.kaizensolutions.trace4cats.zio.extras.{ZSpan, ZTracer}
import sttp.model.{Header, HeaderNames, StatusCode}
import sttp.monad.MonadError
import sttp.tapir.model.ServerRequest
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.interceptor.*
import sttp.tapir.server.interpreter.BodyListener
import sttp.tapir.server.model.ServerResponse
import trace4cats.model.{SpanKind, SpanStatus, TraceHeaders}
import trace4cats.{AttributeValue, ToHeaders}
import zio.*

/**
 * Tapir Request interceptor that traces requests and responses that delegates
 * to the Endpoint interceptor in order to trace the endpoint logic with higher
 * precision (and make use of templated endpoint information).
 * @param tracer
 *   the tracer to use
 * @param dropHeadersWhen
 *   a function that determines whether a header should be dropped from the
 *   trace
 * @param enrichResponseHeadersWithTraceIds
 *   whether to add trace headers to the response
 * @param enrichLogs
 *   whether to add trace headers to the logs
 * @param headerFormat
 *   the format to use for trace headers
 */
final class TraceInterceptor[Env, Err] private (
  private val tracer: ZTracer,
  private val dropHeadersWhen: String => Boolean,
  private val enrichResponseHeadersWithTraceIds: Boolean,
  private val enrichLogs: Boolean,
  private val headerFormat: ToHeaders
) extends RequestInterceptor[ZIO[Env, Err, *]] {

  override def apply[R, B](
    responder: Responder[ZIO[Env, Err, *], B],
    requestHandler: EndpointInterceptor[ZIO[Env, Err, *]] => RequestHandler[ZIO[Env, Err, *], R, B]
  ): RequestHandler[ZIO[Env, Err, *], R, B] = new RequestHandler[ZIO[Env, Err, *], R, B] {
    private val tracingEndpointInterceptor = new TraceEndpointInterceptor[Env, Err](
      tracer,
      dropHeadersWhen,
      enrichResponseHeadersWithTraceIds,
      enrichLogs,
      headerFormat
    )

    override def apply(request: ServerRequest, endpoints: List[ServerEndpoint[R, ZIO[Env, Err, *]]])(implicit
      monad: MonadError[ZIO[Env, Err, *]]
    ): ZIO[Env, Err, RequestResult[B]] =
      requestHandler(tracingEndpointInterceptor)(request, endpoints)
  }
}
object TraceInterceptor {
  def apply[Env, Err](
    tracer: ZTracer,
    dropHeadersWhen: String => Boolean = HeaderNames.isSensitive,
    enrichResponseHeadersWithTraceIds: Boolean = true,
    enrichLogs: Boolean = true,
    headerFormat: ToHeaders = ToHeaders.standard
  ): TraceInterceptor[Env, Err] = new TraceInterceptor(
    tracer,
    dropHeadersWhen,
    enrichResponseHeadersWithTraceIds,
    enrichLogs,
    headerFormat
  )

  def task(
    tracer: ZTracer,
    dropHeadersWhen: String => Boolean = HeaderNames.isSensitive,
    enrichResponseHeadersWithTraceIds: Boolean = true,
    enrichLogs: Boolean = true,
    headerFormat: ToHeaders = ToHeaders.standard
  ): TraceInterceptor[Any, Throwable] =
    apply(tracer, dropHeadersWhen, enrichResponseHeadersWithTraceIds, enrichLogs, headerFormat)

  def rio[R, E <: Throwable](
    tracer: ZTracer,
    dropHeadersWhen: String => Boolean = HeaderNames.isSensitive,
    enrichResponseHeadersWithTraceIds: Boolean = true,
    enrichLogs: Boolean = true,
    headerFormat: ToHeaders = ToHeaders.standard
  ): TraceInterceptor[R, E] =
    apply(tracer, dropHeadersWhen, enrichResponseHeadersWithTraceIds, enrichLogs, headerFormat)
}

private class TraceEndpointInterceptor[Env, Err](
  private val tracer: ZTracer,
  private val dropHeadersWhen: String => Boolean,
  private val enrichResponseHeadersWithTraceIds: Boolean,
  private val enrichLogs: Boolean,
  private val headerFormat: ToHeaders
) extends EndpointInterceptor[ZIO[Env, Err, *]] {
  override def apply[B](
    responder: Responder[ZIO[Env, Err, *], B],
    endpointHandler: EndpointHandler[ZIO[Env, Err, *], B]
  ): EndpointHandler[ZIO[Env, Err, *], B] = new EndpointHandler[ZIO[Env, Err, *], B] {

    override def onDecodeSuccess[A, U, I](
      ctx: DecodeSuccessContext[ZIO[Env, Err, *], A, U, I]
    )(implicit
      monad: MonadError[ZIO[Env, Err, *]],
      bodyListener: BodyListener[ZIO[Env, Err, *], B]
    ): ZIO[Env, Err, ServerResponse[B]] = {
      val spanName     = ctx.endpoint.showShort
      val request      = ctx.request
      val traceHeaders = TraceHeaders.of(request.headers.map(h => (h.name, h.value))*)
      tracer.fromHeaders(traceHeaders, name = spanName, kind = SpanKind.Server) { span =>
        val logTraceContext =
          if (enrichLogs) ZIOAspect.annotated(annotations = extractKVHeaders(span, headerFormat).toList*)
          else ZIOAspect.identity

        enrichSpanFromRequest(request, dropHeadersWhen, span) *>
          (endpointHandler.onDecodeSuccess(ctx) @@ logTraceContext)
            .foldZIO(
              error => span.setStatus(SpanStatus.Internal(error.toString)) *> ZIO.fail(error),
              serverResponse =>
                enrichSpanFromResponse(serverResponse, dropHeadersWhen, span).as(
                  if (enrichResponseHeadersWithTraceIds) serverResponse.addHeaders(toHttpHeaders(span, headerFormat))
                  else serverResponse
                )
            )
      }
    }

    override def onSecurityFailure[A](
      ctx: SecurityFailureContext[ZIO[Env, Err, *], A]
    )(implicit
      monad: MonadError[ZIO[Env, Err, *]],
      bodyListener: BodyListener[ZIO[Env, Err, *], B]
    ): ZIO[Env, Err, ServerResponse[B]] =
      endpointHandler.onSecurityFailure(ctx)

    override def onDecodeFailure(
      ctx: DecodeFailureContext
    )(implicit
      monad: MonadError[ZIO[Env, Err, *]],
      bodyListener: BodyListener[ZIO[Env, Err, *], B]
    ): ZIO[Env, Err, Option[ServerResponse[B]]] =
      endpointHandler.onDecodeFailure(ctx)
  }

  private def toHttpHeaders(span: ZSpan, whichHeaders: ToHeaders): Seq[Header] =
    span
      .extractHeaders(whichHeaders)
      .values
      .collect { case (k, v) if v.nonEmpty => Header(k.toString, v) }
      .toSeq

  private def extractKVHeaders(span: ZSpan, whichHeaders: ToHeaders): Map[String, String] =
    span
      .extractHeaders(whichHeaders)
      .values
      .collect { case (k, v) if v.nonEmpty => (k.toString, v) }

  private def enrichSpanFromRequest(
    request: ServerRequest,
    dropHeadersWhen: String => Boolean,
    span: ZSpan
  ): UIO[Unit] =
    if (span.isSampled) span.putAll(requestFields(request.headers, dropHeadersWhen)*)
    else ZIO.unit

  private def enrichSpanFromResponse[A](
    response: ServerResponse[A],
    dropHeadersWhen: String => Boolean,
    span: ZSpan
  ): UIO[Unit] = {
    val respFields = {
      val statusCodeField = "resp.status.code" -> AttributeValue.intToTraceValue(response.code.code)
      statusCodeField +: responseFields(response.headers, dropHeadersWhen)
    }
    val spanRespAttrs = if (span.isSampled) span.putAll(respFields*) else ZIO.unit
    spanRespAttrs *> span.setStatus(toSpanStatus(response.code))
  }

  private def toSpanStatus(value: StatusCode): SpanStatus =
    value match {
      case StatusCode.BadRequest         => SpanStatus.Internal("Bad Request")
      case StatusCode.Unauthorized       => SpanStatus.Unauthenticated
      case StatusCode.Forbidden          => SpanStatus.PermissionDenied
      case StatusCode.NotFound           => SpanStatus.NotFound
      case StatusCode.TooManyRequests    => SpanStatus.Unavailable
      case StatusCode.BadGateway         => SpanStatus.Unavailable
      case StatusCode.ServiceUnavailable => SpanStatus.Unavailable
      case StatusCode.GatewayTimeout     => SpanStatus.Unavailable
      case status if status.isSuccess    => SpanStatus.Ok
      case _                             => SpanStatus.Unknown
    }

  private def requestFields(
    hs: Seq[Header],
    dropHeadersWhen: String => Boolean
  ): Seq[(String, AttributeValue)] =
    headerFields(hs, "req", dropHeadersWhen)

  private def responseFields(
    hs: Seq[Header],
    dropHeadersWhen: String => Boolean
  ): Seq[(String, AttributeValue)] =
    headerFields(hs, "resp", dropHeadersWhen)

  private def headerFields(
    hs: Seq[Header],
    `type`: String,
    dropHeadersWhen: String => Boolean
  ): Seq[(String, AttributeValue)] =
    hs.filter(h => !dropHeadersWhen(h.name)).map { h =>
      (s"${`type`}.header.${h.name}", h.value: AttributeValue)
    }
}
