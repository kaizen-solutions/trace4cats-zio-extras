package io.kaizensolutions.trace4cats.zio.extras.ziohttp.client

import trace4cats.model.AttributeValue.{LongValue, StringValue}
import trace4cats.model.SemanticAttributeKeys.{serviceHostname, servicePort}
import trace4cats.model.{AttributeValue, SampleDecision, SemanticAttributeKeys, SpanKind}
import trace4cats.{ErrorHandler, ToHeaders}
import io.kaizensolutions.trace4cats.zio.extras.ZTracer
import io.kaizensolutions.trace4cats.zio.extras.ziohttp.toSpanStatus
import zhttp.http.*
import zhttp.service.Client.Config
import zhttp.service.client.ClientSSLHandler.ClientSSLOptions
import zhttp.service.{ChannelFactory, Client, EventLoopGroup}
import zio.*

object ZioHttpClientTracer {
  def makeRequest(
    tracer: ZTracer,
    request: Request,
    clientConfig: Config,
    toHeaders: ToHeaders = ToHeaders.standard,
    spanNamer: Request => String = methodWithPathSpanNamer,
    errorHandler: ErrorHandler = ErrorHandler.empty
  ): ZIO[EventLoopGroup & ChannelFactory, Throwable, Response] = {
    val nameOfRequest = spanNamer(request)
    tracer.withSpan(name = nameOfRequest, kind = SpanKind.Client, errorHandler = errorHandler) { span =>
      val traceHeaders            = span.extractHeaders(toHeaders)
      val zioHttpTraceHeaders     = Headers(traceHeaders.values.map { case (header, value) => (header.toString, value) })
      val requestWithTraceHeaders = request.updateHeaders(_ ++ zioHttpTraceHeaders)

      val enrichWithAttributes =
        if (span.context.traceFlags.sampled == SampleDecision.Include) span.putAll(toAttributes(request))
        else ZIO.unit

      enrichWithAttributes *>
        Client.request(requestWithTraceHeaders, clientConfig).tap { response =>
          val spanStatus     = toSpanStatus(response.status)
          val respAttributes = toAttributes(response)
          span.setStatus(spanStatus) *> span.putAll(respAttributes)
        }
    }
  }

  def makeTracedRequest(
    request: Request,
    clientConfig: Config,
    toHeaders: ToHeaders = ToHeaders.standard,
    spanNamer: Request => String = methodWithPathSpanNamer,
    errorHandler: ErrorHandler = ErrorHandler.empty
  ): ZIO[EventLoopGroup & ChannelFactory & ZTracer, Throwable, Response] =
    ZIO
      .service[ZTracer]
      .flatMap(tracer =>
        makeRequest(
          tracer = tracer,
          request = request,
          clientConfig = clientConfig,
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
    content: HttpData = HttpData.empty,
    ssl: ClientSSLOptions = ClientSSLOptions.DefaultSSL,
    toHeaders: ToHeaders = ToHeaders.standard,
    spanNamer: Request => String = methodWithPathSpanNamer,
    errorHandler: ErrorHandler = ErrorHandler.empty
  ): ZIO[EventLoopGroup & ChannelFactory, Throwable, Response] =
    ZIO
      .fromEither(URL.fromString(url))
      .flatMap(url =>
        makeRequest(
          tracer = tracer,
          request = Request(version = Version.Http_1_1, method = method, url = url, headers = headers, data = content),
          clientConfig = Config(ssl = Some(ssl)),
          toHeaders = toHeaders,
          spanNamer = spanNamer,
          errorHandler = errorHandler
        )
      )

  def tracedRequest(
    url: String,
    method: Method = Method.GET,
    headers: Headers = Headers.empty,
    content: HttpData = HttpData.empty,
    ssl: ClientSSLOptions = ClientSSLOptions.DefaultSSL,
    toHeaders: ToHeaders = ToHeaders.standard,
    spanNamer: Request => String = methodWithPathSpanNamer,
    errorHandler: ErrorHandler = ErrorHandler.empty
  ): ZIO[EventLoopGroup & ChannelFactory & ZTracer, Throwable, Response] =
    ZIO
      .service[ZTracer]
      .flatMap(tracer =>
        request(
          tracer = tracer,
          url = url,
          method = method,
          headers = headers,
          content = content,
          ssl = ssl,
          toHeaders = toHeaders,
          spanNamer = spanNamer,
          errorHandler = errorHandler
        )
      )

  def methodWithPathSpanNamer(req: Request): String =
    s"${req.method.toString()} ${req.url.path.toString()}"

  private def toAttributes(req: Request): Map[String, AttributeValue] =
    Map[String, AttributeValue](
      SemanticAttributeKeys.httpFlavor -> req.version.toJava.toString,
      SemanticAttributeKeys.httpMethod -> req.method.toString(),
      SemanticAttributeKeys.httpUrl    -> req.url.path.toString
    ) ++
      req.url.host.map(host => serviceHostname -> StringValue(host)) ++
      req.url.port.map(port => servicePort -> LongValue(port.toLong))

  private def toAttributes(resp: Response): Map[String, AttributeValue] =
    Map[String, AttributeValue](SemanticAttributeKeys.httpStatusCode -> resp.status.code)
}
