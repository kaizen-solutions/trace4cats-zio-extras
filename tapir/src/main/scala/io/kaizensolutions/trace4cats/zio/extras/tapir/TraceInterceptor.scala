package io.kaizensolutions.trace4cats.zio.extras.tapir

import io.kaizensolutions.trace4cats.zio.extras.{ZSpan, ZTracer}
import sttp.model.{Header, HeaderNames, StatusCode}
import sttp.monad.MonadError
import sttp.tapir.model.ServerRequest
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.interceptor.*
import sttp.tapir.server.model.ServerResponse
import trace4cats.model.{SpanKind, SpanStatus, TraceHeaders}
import trace4cats.{AttributeValue, ToHeaders}
import zio.*

final class TraceInterceptor private (
  private val tracer: ZTracer,
  private val dropHeadersWhen: String => Boolean,
  private val spanNamer: PartialFunction[ServerRequest, String],
  private val enrichResponseHeadersWithTraceIds: Boolean,
  private val enrichLogs: Boolean,
  private val headerFormat: ToHeaders
) extends RequestInterceptor[Task] {

  override def apply[R, B](
    responder: Responder[Task, B],
    requestHandler: EndpointInterceptor[Task] => RequestHandler[Task, R, B]
  ): RequestHandler[Task, R, B] = new RequestHandler[Task, R, B] {
    private def spanNamerTotal(input: ServerRequest): String =
      if (spanNamer.isDefinedAt(input)) spanNamer(input)
      else input.showShort

    override def apply(request: ServerRequest, endpoints: List[ServerEndpoint[R, Task]])(implicit
      monad: MonadError[Task]
    ): Task[RequestResult[B]] =
      if (endpoints.nonEmpty) {
        // only run tracing if there are endpoints to match
        val spanName     = spanNamerTotal(request)
        val traceHeaders = TraceHeaders.of(request.headers.map(h => (h.name, h.value))*)

        tracer.fromHeaders(traceHeaders, name = spanName, kind = SpanKind.Server) { span =>
          val logTraceContext =
            if (enrichLogs) ZIOAspect.annotated(annotations = extractKVHeaders(span, headerFormat).toList*)
            else ZIOAspect.identity

          enrichSpanFromRequest(request, dropHeadersWhen, span) *>
            (requestHandler(EndpointInterceptor.noop)(request, endpoints) @@ logTraceContext)
              .foldZIO(
                error => span.setStatus(SpanStatus.Internal(error.toString)) *> ZIO.fail(error),
                {
                  case res @ RequestResult.Response(response) =>
                    enrichSpanFromResponse(response, dropHeadersWhen, span).as(
                      if (enrichResponseHeadersWithTraceIds)
                        RequestResult.Response(response.addHeaders(toHttpHeaders(span, headerFormat)))
                      else res
                    )

                  case res @ RequestResult.Failure(failures) =>
                    span
                      .putAll(
                        failures.map(f =>
                          s"error.${f.failingInput.show}" -> AttributeValue.stringToTraceValue(f.failure.toString)
                        )*
                      )
                      .as(res)
                }
              )
        }
      } else requestHandler(EndpointInterceptor.noop)(request, endpoints)
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
    val respFields    = responseFields(response.headers, dropHeadersWhen)
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
  ): List[(String, AttributeValue)] =
    headerFields(hs, "req", dropHeadersWhen)

  private def responseFields(
    hs: Seq[Header],
    dropHeadersWhen: String => Boolean
  ): List[(String, AttributeValue)] =
    headerFields(hs, "resp", dropHeadersWhen)

  private def headerFields(
    hs: Seq[Header],
    `type`: String,
    dropHeadersWhen: String => Boolean
  ): List[(String, AttributeValue)] =
    hs.filter(h => !dropHeadersWhen(h.name))
      .map { h =>
        (s"${`type`}.header.${h.name}", h.value: AttributeValue)
      }
      .toList
}
object TraceInterceptor {
  def apply(
    tracer: ZTracer,
    dropHeadersWhen: String => Boolean = HeaderNames.isSensitive,
    spanNamer: PartialFunction[ServerRequest, String] = _.showShort,
    enrichResponseHeadersWithTraceIds: Boolean = true,
    enrichLogs: Boolean = true,
    headerFormat: ToHeaders = ToHeaders.standard
  ): TraceInterceptor = new TraceInterceptor(
    tracer,
    dropHeadersWhen,
    spanNamer,
    enrichResponseHeadersWithTraceIds,
    enrichLogs,
    headerFormat
  )
}
