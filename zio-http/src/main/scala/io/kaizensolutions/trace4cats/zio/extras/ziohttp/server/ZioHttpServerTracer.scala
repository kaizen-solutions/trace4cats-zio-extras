package io.kaizensolutions.trace4cats.zio.extras.ziohttp.server

import io.kaizensolutions.trace4cats.zio.extras.ziohttp.{extractTraceHeaders, toSpanStatus}
import io.kaizensolutions.trace4cats.zio.extras.{ZSpan, ZTracer}
import trace4cats.model.AttributeValue.{LongValue, StringValue}
import trace4cats.model.SemanticAttributeKeys.*
import trace4cats.model.{AttributeValue, SpanKind, SpanStatus}
import trace4cats.{ErrorHandler, ToHeaders}
import zio.*
import zio.http.*

object ZioHttpServerTracer {

  /**
   * SpanNamer is a custom mapping so if you had a path parameter like
   * /user/1234, you could map it to /user/:id to reduce the cardinality of your
   * traces
   */
  type SpanNamer = PartialFunction[Request, String]

  /**
   * Tracing middleware for ZIO HTTP apps
   *
   * @param dropHeadersWhen
   *   drop headers when this predicate is true
   * @param spanNamer
   *   is used when you want to override the default span name especially when
   *   you have path parameters
   * @param errorHandler
   *   is used to handle errors
   * @param enrichLogs
   *   whether to enrich logs with trace information
   * @param logHeaders
   *   which headers to log
   * @return
   */
  def trace(
    dropHeadersWhen: String => Boolean = SensitiveHeaders.contains,
    spanNamer: SpanNamer = Map.empty[Request, String],
    errorHandler: ErrorHandler = ErrorHandler.empty,
    enrichLogs: Boolean = false,
    logHeaders: ToHeaders = ToHeaders.standard
  ): RequestHandlerMiddleware.Simple[ZTracer, Nothing] = {
    val spanNamerTotal: Request => String = { request =>
      val httpMethod = request.method.toString()
      if (spanNamer.isDefinedAt(request)) s"$httpMethod ${spanNamer(request)}"
      else s"$httpMethod ${request.url.path.toString()}"
    }

    new RequestHandlerMiddleware.Simple[ZTracer, Nothing] {

      def apply[Env <: ZTracer, Err >: Nothing](
                                                 handler: Handler[Env, Err, Request, Response]
                                               )(implicit trace: Trace): Handler[Env, Err, Request, Response] =
        Handler.fromFunctionZIO[Request]{ request =>
          val traceHeaders = extractTraceHeaders(request.headers)
          val nameOfSpan = spanNamerTotal(request)

            ZIO.serviceWithZIO[ZTracer](
              _.fromHeaders(traceHeaders, nameOfSpan, SpanKind.Server, errorHandler) { span =>
                val logTraceContext =
                  if (enrichLogs) {
                    ZIOAspect.annotated(annotations = extractKVHeaders(span, logHeaders).toList*)
                  } else ZIOAspect.identity

                enrichSpanFromRequest(request, dropHeadersWhen, span) *>
                  // NOTE: We need to call handler.runZIO and have the code executed within our span for propagation to take place
                  (handler.runZIO(request) @@ logTraceContext).onExit {
                    case Exit.Success(response) => enrichSpanFromResponse(response, dropHeadersWhen, span)
                    case Exit.Failure(cause) => span.setStatus(SpanStatus.Internal(cause.prettyPrint))
                  }
              }
            )
        }
    }
  }

  def injectHeaders(whichHeaders: ToHeaders = ToHeaders.standard): RequestHandlerMiddleware.Simple[ZTracer, Response] =
    new RequestHandlerMiddleware.Simple[ZTracer, Response] {
    def apply[Env <: ZTracer, Err >: Response](
                                               handler: Handler[Env, Err, Request, Response]
                                             )(implicit trace: Trace): Handler[Env, Err, Request, Response] = {
      Handler.fromFunctionZIO( request =>
        ZTracer.retrieveCurrentSpan.flatMap { span =>
          val headers = toHttpHeaders(span, whichHeaders)
          handler(request).fold(
            _ => Response.status(Status.InternalServerError).addHeaders(headers),
            _.addHeaders(headers)
          )
        }
      )
    }
  }

  private def toHttpHeaders(span: ZSpan, whichHeaders: ToHeaders): Headers =
    Headers(
      span
      .extractHeaders(whichHeaders)
      .values
      .collect { case (k, v) if v.nonEmpty => Header.Custom(k.toString, v) }
    )

  private def extractKVHeaders(span: ZSpan, whichHeaders: ToHeaders): Map[String, String] =
      span
        .extractHeaders(whichHeaders)
        .values
        .collect { case (k, v) if v.nonEmpty => (k.toString, v) }

  private def enrichSpanFromRequest(request: Request, dropHeadersWhen: String => Boolean, span: ZSpan): UIO[Unit] = {
    val reqFields = requestFields(request, dropHeadersWhen)
    if (span.isSampled) span.putAll(reqFields*)
    else ZIO.unit
  }

  private def enrichSpanFromResponse(response: Response, dropHeadersWhen: String => Boolean, span: ZSpan): UIO[Unit] = {
    val respFields    = responseFields(response, dropHeadersWhen)
    val spanRespAttrs = if (span.isSampled) span.putAll(respFields*) else ZIO.unit
    spanRespAttrs *> span.setStatus(toSpanStatus(response.status))
  }

  private def requestFields(
    req: Request,
    dropHeadersWhen: String => Boolean
  ): Chunk[(String, AttributeValue)] =
    Chunk[(String, AttributeValue)](
      httpFlavor -> renderHttpVersion(req.version),
      httpMethod -> req.method.toString(),
      httpUrl    -> req.url.path.toString
    ) ++ headerFields(headers = req.headers, `type` = "req", dropWhen = dropHeadersWhen) ++
      req.url.host.map(host => serviceHostname -> StringValue(host)) ++
      req.url.port.map(port => servicePort -> LongValue(port.toLong))

  private def responseFields(
    resp: Response,
    dropHeadersWhen: String => Boolean
  ): List[(String, AttributeValue)] =
    List[(String, AttributeValue)](httpStatusCode -> resp.status.code) ++ headerFields(
      resp.headers,
      "resp",
      dropHeadersWhen
    )

  private def headerFields(
    headers: Headers,
    `type`: String,
    dropWhen: String => Boolean
  ): Chunk[(String, AttributeValue)] =
    Chunk.fromIterable(headers.collect {
      case header if !dropWhen(header.headerName) =>
        s"${`type`}.header.${header.headerName}" -> AttributeValue.stringToTraceValue(header.renderedValue)
    })

  private val SensitiveHeaders: Set[String] = Set(
    Header.Authorization,
    Header.Cookie,
    Header.SetCookie
  ).map(_.name)

  private def renderHttpVersion(in: Version): String = {
    val http = "HTTP"
    val version =
      in match {
        case Version.Http_1_0 => "1.0"
        case Version.Http_1_1 => "1.1"
      }
    s"$http/$version"
  }
}
