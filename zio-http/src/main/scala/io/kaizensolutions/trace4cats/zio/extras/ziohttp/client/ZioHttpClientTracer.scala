package io.kaizensolutions.trace4cats.zio.extras.ziohttp.client

import io.kaizensolutions.trace4cats.zio.extras.ZTracer
import io.kaizensolutions.trace4cats.zio.extras.ziohttp.toSpanStatus
import trace4cats.model.AttributeValue.{LongValue, StringValue}
import trace4cats.model.SemanticAttributeKeys.{serviceHostname, servicePort}
import trace4cats.model.{AttributeValue, SampleDecision, SemanticAttributeKeys, SpanKind}
import trace4cats.{ErrorHandler, ToHeaders}
import zio.*
import zio.http.*

/**
 * Warning: This API is under construction
 */
object ZioHttpClientTracer {
  def makeRequest(
    tracer: ZTracer,
    request: Request,
    toHeaders: ToHeaders = ToHeaders.standard,
    spanNamer: Map[Request, String] = Map.empty[Request, String],
    errorHandler: ErrorHandler = ErrorHandler.empty
  ): ZIO[Client & Scope, Throwable, Response] = {
    val nameOfRequest = spanNamer.getOrElse(request, defaultSpanNamer(request))
    tracer.withSpan(name = nameOfRequest, kind = SpanKind.Client, errorHandler = errorHandler) { span =>
      val traceHeaders = span.extractHeaders(toHeaders)
      val zioHttpTraceHeaders = Headers(traceHeaders.values.map { case (header, value) =>
        Header.Custom(header.toString, value)
      })
      val requestWithTraceHeaders = request.updateHeaders(_ ++ zioHttpTraceHeaders)

      val enrichWithAttributes =
        span.putAll(toAttributes(request)).when(span.context.traceFlags.sampled == SampleDecision.Include)

      enrichWithAttributes *>
        Client.streaming(requestWithTraceHeaders).tap { response =>
          val spanStatus     = toSpanStatus(response.status)
          val respAttributes = toAttributes(response)
          span.setStatus(spanStatus) *> span.putAll(respAttributes)
        }
    }
  }

  def makeTracedRequest(
    request: Request,
    toHeaders: ToHeaders = ToHeaders.standard,
    spanNamer: Map[Request, String] = Map.empty[Request, String],
    errorHandler: ErrorHandler = ErrorHandler.empty
  ): ZIO[Client & Scope & ZTracer, Throwable, Response] =
    ZIO
      .service[ZTracer]
      .flatMap(tracer =>
        makeRequest(
          tracer = tracer,
          request = request,
          toHeaders = toHeaders,
          spanNamer = spanNamer,
          errorHandler = errorHandler
        )
      )

  def request(
    tracer: ZTracer,
    url: String,
    method: Method = Method.GET,
    headers: Headers = Headers.empty,
    body: Body = Body.empty,
    toHeaders: ToHeaders = ToHeaders.standard,
    spanNamer: Map[Request, String] = Map.empty[Request, String],
    errorHandler: ErrorHandler = ErrorHandler.empty
  ): ZIO[Client & Scope, Throwable, Response] =
    ZIO
      .fromEither(URL.decode(url))
      .flatMap(url =>
        makeRequest(
          tracer = tracer,
          request = Request(method = method, url = url, body = body).addHeaders(headers),
          toHeaders = toHeaders,
          spanNamer = spanNamer,
          errorHandler = errorHandler
        )
      )

  def tracedRequest(
    url: String,
    method: Method = Method.GET,
    headers: Headers = Headers.empty,
    body: Body = Body.empty,
    toHeaders: ToHeaders = ToHeaders.standard,
    spanNamer: Map[Request, String] = Map.empty[Request, String],
    errorHandler: ErrorHandler = ErrorHandler.empty
  ): ZIO[Client & Scope & ZTracer, Throwable, Response] =
    ZIO
      .service[ZTracer]
      .flatMap(tracer =>
        request(
          tracer = tracer,
          url = url,
          method = method,
          headers = headers,
          body = body,
          toHeaders = toHeaders,
          spanNamer = spanNamer,
          errorHandler = errorHandler
        )
      )

  private def defaultSpanNamer(req: Request): String =
    s"${req.method.toString()} ${req.url.path.toString()}"

  private def toAttributes(req: Request): Map[String, AttributeValue] =
    Map[String, AttributeValue](
      SemanticAttributeKeys.httpFlavor -> req.version.toString,
      SemanticAttributeKeys.httpMethod -> req.method.toString(),
      SemanticAttributeKeys.httpUrl    -> req.url.path.toString
    ) ++
      req.url.host.map(host => serviceHostname -> StringValue(host)) ++
      req.url.port.map(port => servicePort -> LongValue(port.toLong))

  private def toAttributes(resp: Response): Map[String, AttributeValue] =
    Map[String, AttributeValue](SemanticAttributeKeys.httpStatusCode -> resp.status.code)
}
