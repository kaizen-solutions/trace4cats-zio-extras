package io.kaizensolutions.trace4cats.zio.extras.http4s.server

import cats.data.{Kleisli, OptionT}
import io.janstenpickle.trace4cats.http4s.common.{
  Http4sHeaders,
  Http4sSpanNamer,
  Http4sStatusMapping,
  Request_,
  Response_
}
import io.janstenpickle.trace4cats.model.{SpanKind, SpanStatus}
import io.kaizensolutions.trace4cats.zio.extras.ZTracer
import org.http4s.{HttpRoutes, Request, Response}
import org.typelevel.ci.CIString
import zio._

object ServerZioTracer {
  def traceRoutes[R <: Has[?]](
    tracer: ZTracer,
    routes: HttpRoutes[RIO[R, *]],
    dropHeadersWhen: CIString => Boolean = _ => false
  ): HttpRoutes[RIO[R, *]] =
    Kleisli[
      OptionT[RIO[R, *], *],
      Request[RIO[R, *]],
      Response[RIO[R, *]]
    ] { request =>
      val reqFields    = Http4sHeaders.requestFields(request: Request_, dropHeadersWhen)
      val traceHeaders = Http4sHeaders.converter.from(request.headers)
      val nameOfSpan   = Http4sSpanNamer.methodWithPath(request)

      val tracedResponse =
        tracer.fromHeaders(traceHeaders, kind = SpanKind.Server, nameOfSpan) { span =>
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
}
