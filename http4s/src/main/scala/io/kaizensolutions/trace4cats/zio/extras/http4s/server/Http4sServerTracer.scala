package io.kaizensolutions.trace4cats.zio.extras.http4s.server

import cats.data.{Kleisli, OptionT}
import io.janstenpickle.trace4cats.ErrorHandler
import io.janstenpickle.trace4cats.http4s.common.{
  Http4sHeaders,
  Http4sSpanNamer,
  Http4sStatusMapping,
  Request_,
  Response_
}
import io.janstenpickle.trace4cats.model.{SpanKind, SpanStatus}
import io.kaizensolutions.trace4cats.zio.extras.ZTracer
import org.http4s.{Headers, HttpApp, HttpRoutes, Request, Response}
import org.typelevel.ci.CIString
import zio.*

object Http4sServerTracer {

  /**
   * This has been adapted from
   * https://github.com/trace4cats/trace4cats-http4s/blob/master/modules/http4s-server/src/main/scala/io/janstenpickle/trace4cats/http4s/server/Http4sResourceKleislis.scala
   * &
   * https://github.com/trace4cats/trace4cats-http4s/blob/master/modules/http4s-server/src/main/scala/io/janstenpickle/trace4cats/http4s/server/ServerTracer.scala
   * in order to preserve the same semantics as the original implementation
   * found in trace4cats-http4s-server but to allow flexibility to vary the ZIO
   * Environment.
   *
   * @param tracer
   *   is the ZTracer to use for tracing
   * @param spanNamer
   *   is the SpanNamer to use for naming the spans
   * @param dropHeadersWhen
   *   is the predicate to use for determining whether to drop the headers
   * @param routes
   *   is the underlying HttpRoutes to trace
   * @tparam R
   *   is the ZIO Environment
   * @return
   *   an HttpRoutes that traces the underlying HttpRoutes
   */
  def traceRoutes[R, E](
    tracer: ZTracer,
    routes: HttpRoutes[ZIO[R, E, *]],
    spanNamer: Http4sSpanNamer = Http4sSpanNamer.methodWithPath,
    dropHeadersWhen: CIString => Boolean = Headers.SensitiveHeaders.contains,
    errorHandler: ErrorHandler = ErrorHandler.empty
  ): HttpRoutes[ZIO[R, E, *]] =
    Kleisli[
      OptionT[ZIO[R, E, *], *],
      Request[ZIO[R, E, *]],
      Response[ZIO[R, E, *]]
    ] { request =>
      val reqFields    = Http4sHeaders.requestFields(request: Request_, dropHeadersWhen)
      val traceHeaders = Http4sHeaders.converter.from(request.headers)
      val nameOfSpan   = spanNamer(request)

      val tracedResponse =
        tracer.fromHeaders(
          headers = traceHeaders,
          kind = SpanKind.Server,
          name = nameOfSpan,
          errorHandler = errorHandler
        ) { span =>
          span.putAll(reqFields *) *>
            routes.run(request).value.onExit {
              case Exit.Success(Some(response)) =>
                span.setStatus(Http4sStatusMapping.toSpanStatus(response.status)) *>
                  span.putAll(Http4sHeaders.responseFields(response: Response_, dropHeadersWhen) *)

              case Exit.Success(None) =>
                span.setStatus(SpanStatus.NotFound)

              case Exit.Failure(cause) =>
                span.setStatus(SpanStatus.Internal(cause.prettyPrint))
            }
        }
      OptionT(tracedResponse)
    }

  /**
   * This has been adapted from
   * https://github.com/trace4cats/trace4cats-http4s/blob/master/modules/http4s-server/src/main/scala/io/janstenpickle/trace4cats/http4s/server/Http4sResourceKleislis.scala
   * &
   * https://github.com/trace4cats/trace4cats-http4s/blob/master/modules/http4s-server/src/main/scala/io/janstenpickle/trace4cats/http4s/server/ServerTracer.scala
   * in order to preserve the same semantics as the original implementation
   * found in trace4cats-http4s-server but to allow flexibility to vary the ZIO
   * Environment.
   *
   * @param tracer
   *   is the ZTracer to use for tracing
   * @param spanNamer
   *   is the SpanNamer to use for naming the spans
   * @param dropHeadersWhen
   *   is the predicate to use for determining whether to drop the headers
   * @param app
   *   is the underlying HttpApp to trace
   * @tparam R
   *   is the ZIO Environment
   * @return
   *   an HttpApp that traces the underlying HttpRoutes
   */
  def traceApp[R, E](
    tracer: ZTracer,
    app: HttpApp[ZIO[R, E, *]],
    spanNamer: Http4sSpanNamer = Http4sSpanNamer.methodWithPath,
    dropHeadersWhen: CIString => Boolean = _ => false,
    errorHandler: ErrorHandler = ErrorHandler.empty
  ): HttpApp[ZIO[R, E, *]] =
    Kleisli[
      ZIO[R, E, *],
      Request[ZIO[R, E, *]],
      Response[ZIO[R, E, *]]
    ] { request =>
      val reqFields    = Http4sHeaders.requestFields(request: Request_, dropHeadersWhen)
      val traceHeaders = Http4sHeaders.converter.from(request.headers)
      val nameOfSpan   = spanNamer(request)

      tracer.fromHeaders(
        headers = traceHeaders,
        kind = SpanKind.Server,
        name = nameOfSpan,
        errorHandler = errorHandler
      ) { span =>
        span.putAll(reqFields *) *>
          app.run(request).onExit {
            case Exit.Success(response) =>
              span.setStatus(Http4sStatusMapping.toSpanStatus(response.status)) *>
                span.putAll(Http4sHeaders.responseFields(response: Response_, dropHeadersWhen) *)

            case Exit.Failure(cause) =>
              span.setStatus(SpanStatus.Internal(cause.prettyPrint))
          }
      }
    }
}
