package io.kaizensolutions.trace4cats.zio.extras.tapir

import trace4cats.model.{AttributeValue, SpanKind, SpanStatus, TraceHeaders}
import io.kaizensolutions.trace4cats.zio.extras.ZTracer
import sttp.model.{HeaderNames, Headers}
import sttp.tapir.Endpoint
import sttp.tapir.internal.*
import sttp.tapir.server.ServerEndpoint
import zio.{Exit, ZIO}

object TapirServerTracer {

  /**
   * @param tracer
   *   is the ZTracer to use for tracing server endpoints
   * @param serverEndpoint
   *   is the server endpoint to be traced
   * @param extractRequestHeaders
   *   extracts the headers from the request
   * @param extractResponseHeaders
   *   extracts the headers from the response
   * @param spanNamer
   *   defines how a span is named
   * @param errorToSpanStatus
   *   maps an error to a span status
   * @param dropHeadersWhen
   *   is the predicate to use for determining whether to drop the headers
   * @tparam I
   *   Tapir's I (Input)
   * @tparam E
   *   Tapir's E (Error)
   * @tparam O
   *   Tapir's O (Output)
   * @tparam R
   *   Tapir's R (endpoint capabilities)
   * @tparam EffectEnv
   *   is the ZIO Effect's environment
   * @tparam EffectErr
   *   is the ZIO Effect's error type
   * @return
   */
  def traceEndpoint[I, E, O, R, EffectEnv, EffectErr](
    tracer: ZTracer,
    serverEndpoint: ServerEndpoint.Full[Unit, Unit, I, E, O, R, ZIO[EffectEnv, EffectErr, *]],
    extractRequestHeaders: I => Headers,
    extractResponseHeaders: O => Headers,
    spanNamer: (Endpoint[?, I, ?, ?, ?], I) => String = methodWithPathTemplateSpanNamer[I],
    errorToSpanStatus: E => SpanStatus = (e: E) => SpanStatus.Internal(e.toString),
    dropHeadersWhen: String => Boolean = HeaderNames.isSensitive
  ): ServerEndpoint.Full[Unit, Unit, I, E, O, R, ZIO[EffectEnv, EffectErr, *]] =
    ServerEndpoint.public(
      endpoint = serverEndpoint.endpoint,
      logic = { monadError => input =>
        val reqHeaders   = extractRequestHeaders(input)
        val traceHeaders = TraceHeaders.of(reqHeaders.headers.map(h => (h.name, h.value))*)
        val spanName     = spanNamer(serverEndpoint.endpoint, input)

        tracer.fromHeaders(headers = traceHeaders, kind = SpanKind.Server, name = spanName) { span =>
          tracer.putAll(requestFields(reqHeaders, dropHeadersWhen)*) *>
            serverEndpoint
              .logic(monadError)(())(input)
              .flatMap {
                case left @ Left(error) =>
                  span
                    .setStatus(errorToSpanStatus(error))
                    .as(left)

                case right @ Right(success) =>
                  tracer
                    .putAll(responseFields(extractResponseHeaders(success), dropHeadersWhen)*)
                    .as(right)
              }
              .onExit {
                case Exit.Failure(cause) if cause.died =>
                  span.setStatus(SpanStatus.Internal(cause.prettyPrint))

                case _ =>
                  ZIO.unit
              }
        }
      }
    )

  def methodWithPathTemplateSpanNamer[I]: (Endpoint[?, I, ?, ?, ?], I) => String =
    (endpoint, _) => endpoint.input.method.fold("ANY")(_.method) + " " + endpoint.showPathTemplate()

  private def requestFields(
    hs: Headers,
    dropHeadersWhen: String => Boolean
  ): List[(String, AttributeValue)] =
    headerFields(hs, "req", dropHeadersWhen)

  private def responseFields(
    hs: Headers,
    dropHeadersWhen: String => Boolean
  ): List[(String, AttributeValue)] =
    headerFields(hs, "resp", dropHeadersWhen)

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
