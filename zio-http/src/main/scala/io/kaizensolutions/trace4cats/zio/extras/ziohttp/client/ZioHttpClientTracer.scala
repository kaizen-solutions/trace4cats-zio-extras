package io.kaizensolutions.trace4cats.zio.extras.ziohttp.client

import io.kaizensolutions.trace4cats.zio.extras.ZTracer
import io.kaizensolutions.trace4cats.zio.extras.ziohttp.toSpanStatus
import trace4cats.model.AttributeValue.{LongValue, StringValue}
import trace4cats.model.SemanticAttributeKeys.{serviceHostname, servicePort}
import trace4cats.model.{AttributeValue, SampleDecision, SemanticAttributeKeys, SpanKind}
import trace4cats.{ErrorHandler, ToHeaders}
import zio.*
import zio.http.*

object ZioHttpClientTracer {
  def makeRequest(
    tracer: ZTracer,
    request: Request,
    toHeaders: ToHeaders = ToHeaders.standard,
    spanNamer: Request => String = methodWithPathSpanNamer,
    errorHandler: ErrorHandler = ErrorHandler.empty
  ): ZIO[Client, Throwable, Response] = {
    val nameOfRequest = spanNamer(request)
    tracer.withSpan(name = nameOfRequest, kind = SpanKind.Client, errorHandler = errorHandler) { span =>
      val traceHeaders = span.extractHeaders(toHeaders)
      val zioHttpTraceHeaders = Headers(traceHeaders.values.map { case (header, value) =>
        Header.Custom(header.toString, value)
      })
      val requestWithTraceHeaders = request.updateHeaders(_ ++ zioHttpTraceHeaders)

      val enrichWithAttributes =
        span.putAll(toAttributes(request)).when(span.context.traceFlags.sampled == SampleDecision.Include)

      enrichWithAttributes *>
        Client.request(requestWithTraceHeaders).tap { response =>
          val spanStatus     = toSpanStatus(response.status)
          val respAttributes = toAttributes(response)
          span.setStatus(spanStatus) *> span.putAll(respAttributes)
        }
    }
  }

  def makeTracedRequest(
    request: Request,
    toHeaders: ToHeaders = ToHeaders.standard,
    spanNamer: Request => String = methodWithPathSpanNamer,
    errorHandler: ErrorHandler = ErrorHandler.empty
  ): ZIO[Client & ZTracer, Throwable, Response] =
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
    content: Body = Body.empty,
    toHeaders: ToHeaders = ToHeaders.standard,
    spanNamer: Request => String = methodWithPathSpanNamer,
    errorHandler: ErrorHandler = ErrorHandler.empty
  ): ZIO[Client, Throwable, Response] =
    ZIO
      .fromEither(URL.decode(url))
      .flatMap(url =>
        makeRequest(
          tracer = tracer,
          request = Request.default(method, url, content).addHeaders(headers),
          toHeaders = toHeaders,
          spanNamer = spanNamer,
          errorHandler = errorHandler
        )
      )

  def tracedRequest(
    url: String,
    method: Method = Method.GET,
    headers: Headers = Headers.empty,
    content: Body = Body.empty,
    toHeaders: ToHeaders = ToHeaders.standard,
    spanNamer: Request => String = methodWithPathSpanNamer,
    errorHandler: ErrorHandler = ErrorHandler.empty
  ): ZIO[Client & ZTracer, Throwable, Response] =
    ZIO
      .service[ZTracer]
      .flatMap(tracer =>
        request(
          tracer = tracer,
          url = url,
          method = method,
          headers = headers,
          content = content,
          toHeaders = toHeaders,
          spanNamer = spanNamer,
          errorHandler = errorHandler
        )
      )

  def methodWithPathSpanNamer(req: Request): String =
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
