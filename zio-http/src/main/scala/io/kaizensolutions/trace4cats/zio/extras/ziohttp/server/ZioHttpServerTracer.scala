package io.kaizensolutions.trace4cats.zio.extras.ziohttp.server

import io.janstenpickle.trace4cats.ErrorHandler
import io.janstenpickle.trace4cats.model.AttributeValue.{LongValue, StringValue}
import io.janstenpickle.trace4cats.model.SemanticAttributeKeys.*
import io.janstenpickle.trace4cats.model.{AttributeValue, SpanKind, SpanStatus}
import io.kaizensolutions.trace4cats.zio.extras.ZTracer
import io.kaizensolutions.trace4cats.zio.extras.ziohttp.{extractTraceHeaders, toSpanStatus}
import zhttp.http.*
import zio.{Chunk, Exit}

object ZioHttpServerTracer {
  type SpanNamer = Request => String

  def traceApp[R, E](
    tracer: ZTracer,
    httpApp: HttpApp[R, E],
    dropHeadersWhen: String => Boolean = SensitiveHeaders.contains,
    spanNamer: SpanNamer = req => s"${req.method.toString()} ${req.url.path.toString()}",
    errorHandler: ErrorHandler = ErrorHandler.empty
  ): HttpApp[R, E] =
    Http.fromOptionFunction[Request] { request =>
      val reqFields    = requestFields(request, dropHeadersWhen)
      val traceHeaders = extractTraceHeaders(request.headers)
      val nameOfSpan   = spanNamer(request)

      tracer.fromHeaders(
        headers = traceHeaders,
        kind = SpanKind.Server,
        name = nameOfSpan,
        errorHandler = errorHandler
      ) { span =>
        span.putAll(reqFields *) *>
          httpApp(request).onExit {
            case Exit.Success(response) =>
              span.setStatus(toSpanStatus(response.status)) *>
                span.putAll(responseFields(response, dropHeadersWhen) *)

            case Exit.Failure(cause) =>
              span.setStatus(SpanStatus.Internal(cause.prettyPrint))
          }
      }
    }

  private def requestFields(
    req: Request,
    dropHeadersWhen: String => Boolean
  ): Chunk[(String, AttributeValue)] =
    Chunk[(String, AttributeValue)](
      httpFlavor -> req.version.toJava.toString,
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
    headers.toChunk.collect {
      case (name, value) if !dropWhen(String.valueOf(name)) =>
        s"${`type`}.header.$name" -> AttributeValue.stringToTraceValue(String.valueOf(value))
    }

  val SensitiveHeaders: Set[String] = Set(
    HeaderNames.authorization,
    HeaderNames.cookie,
    HeaderNames.setCookie
  ).map(String.valueOf(_))
}
