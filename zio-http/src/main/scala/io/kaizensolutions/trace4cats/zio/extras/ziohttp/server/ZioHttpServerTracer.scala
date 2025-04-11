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
    errorHandler: ErrorHandler = ErrorHandler.empty,
    enrichLogs: Boolean = false,
    logHeaders: ToHeaders = ToHeaders.standard
  ): Middleware[ZTracer] = new Middleware[ZTracer] {
    override def apply[Env <: ZTracer, Err](routes: Routes[Env, Err]): Routes[Env, Err] =
      Routes.fromIterable(
        routes.routes.map(route =>
          route.transform(h =>
            Handler.scoped[Env](
              handler { (request: Request) =>
                val traceHeaders = extractTraceHeaders(request.headers)
                val nameOfSpan   = route.routePattern.render

                ZIO.serviceWithZIO[ZTracer](
                  _.fromHeaders(traceHeaders, nameOfSpan, SpanKind.Server, errorHandler) { span =>
                    val logTraceContext =
                      if (enrichLogs) ZIOAspect.annotated(annotations = extractKVHeaders(span, logHeaders).toList*)
                      else ZIOAspect.identity

                    enrichSpanFromRequest(request, dropHeadersWhen, span) *>
                      // NOTE: We need to call handler.runZIO and have the code executed within our span for propagation to take place
                      (h.runZIO(request) @@ logTraceContext).onExit {
                        case Exit.Success(response) => enrichSpanFromResponse(response, dropHeadersWhen, span)
                        case Exit.Failure(cause)    => span.setStatus(SpanStatus.Internal(cause.prettyPrint))
                      }

                  }
                )
              }
            )
          )
        )
      )
  }

  /**
   * Injects span headers into the response, so that its easier to look them up
   * in logs or monitoring software
   *
   * Note: This only works in conjunction with the `trace` middleware, since it
   * needs access to the span created for that request
   */
  def injectHeaders(whichHeaders: ToHeaders = ToHeaders.standard): Middleware[ZTracer] =
    new Middleware[ZTracer] {
      override def apply[Env <: ZTracer, Err](routes: Routes[Env, Err]): Routes[Env, Err] = {
        routes.transform(h =>
          Handler.scoped[Env](
            handler { (request: Request) =>
              ZTracer.retrieveCurrentSpan.flatMap { span =>
                val headers = toHttpHeaders(span, whichHeaders)
                h(request).fold(
                  _.addHeaders(headers),
                  _.addHeaders(headers)
                )
              }
            }
          )
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
        case Version.Default  => "Default"
        case Version.Http_1_0 => "1.0"
        case Version.Http_1_1 => "1.1"
      }
    s"$http/$version"
  }
}
