package io.kaizensolutions.trace4cats.zio.extras.ziohttp.server

import io.kaizensolutions.trace4cats.zio.extras.ZTracer
import io.kaizensolutions.trace4cats.zio.extras.ziohttp.{extractTraceHeaders, toSpanStatus}
import trace4cats.{ErrorHandler, TraceHeaders}
import trace4cats.model.AttributeValue.{LongValue, StringValue}
import trace4cats.model.SemanticAttributeKeys.*
import trace4cats.model.{AttributeValue, SpanKind, SpanStatus}
import zio.*
import zio.http.*
import zio.http.model.*
import zio.http.model.Headers.Header

object ZioHttpServerTracer {

  /**
   * SpanNamer is a custom mapping so if you had a path parameter like
   * /user/1234, you could map it to /user/:id to reduce the cardinality of your
   * traces
   */
  type SpanNamer = PartialFunction[Request, String]

  def trace(
    dropHeadersWhen: String => Boolean = SensitiveHeaders.contains,
    spanNamer: SpanNamer = Map.empty[Request, String],
    errorHandler: ErrorHandler = ErrorHandler.empty
  ): HttpAppMiddleware[ZTracer, Nothing] =
    new HttpAppMiddleware[ZTracer, Nothing] {

      private val spanNamerTotal: Request => String = {
        val default = (req: Request) => s"${req.method.toString()} ${req.url.path.toString()}"
        spanNamer.applyOrElse(_, default)
      }

      override def apply[R1 <: ZTracer, Err1 >: Nothing](
        http: Http[R1, Err1, Request, Response]
      )(implicit trace: Trace): Http[R1, Err1, Request, Response] =
        Http.fromOptionalHandlerZIO[Request] { request =>
          val reqFields    = requestFields(request, dropHeadersWhen)
          val traceHeaders = extractTraceHeaders(request.headers)
          val nameOfSpan   = spanNamerTotal(request)

          http
            .runHandler(request)
            .mapError(Option(_))
            .flatMap {
              case Some(handler) =>
                ZIO.succeed(
                  spanHandler(request, handler, traceHeaders, dropHeadersWhen, nameOfSpan, reqFields, errorHandler)
                )

              case None =>
                ZIO.fail(None)
            }
        }
    }

  private def spanHandler[Env <: ZTracer, Err](
    request: Request,
    handler: Handler[Env, Err, Request, Response],
    traceHeaders: TraceHeaders,
    dropHeadersWhen: String => Boolean,
    nameOfSpan: String,
    requestFields: Chunk[(String, AttributeValue)],
    errorHandler: ErrorHandler
  ): Handler[Env, Err, Any, Response] =
    Handler.fromZIO(
      ZIO.serviceWithZIO[ZTracer](
        _.fromHeaders(traceHeaders, nameOfSpan, SpanKind.Server, errorHandler) { span =>
          span.putAll(requestFields*) *>
            // NOTE: We need to call handler.runZIO and have the code executed within our span for propagation to take place
            handler.runZIO(request).onExit {
              case Exit.Success(response) =>
                span.putAll(responseFields(response, dropHeadersWhen)*) *>
                  span.setStatus(toSpanStatus(response.status))

              case Exit.Failure(cause) =>
                span.setStatus(SpanStatus.Internal(cause.prettyPrint))
            }
        }
      )
    )

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
      case Header(name, value) if !dropWhen(String.valueOf(name)) =>
        s"${`type`}.header.$name" -> AttributeValue.stringToTraceValue(String.valueOf(value))
    })

  val SensitiveHeaders: Set[String] = Set(
    HeaderNames.authorization,
    HeaderNames.cookie,
    HeaderNames.setCookie
  ).map(String.valueOf(_))

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
