package io.kaizensolutions.trace4cats.zio.extras

import io.janstenpickle.trace4cats.model.{SpanStatus, TraceHeaders}
import zhttp.http.{Headers, Status}

package object ziohttp {
  private[ziohttp] def extractTraceHeaders(headers: Headers): TraceHeaders =
    TraceHeaders.of(headers.toChunk.map { case (k, v) => (String.valueOf(k), String.valueOf(v)) }.toMap)

  // Adapted from io.janstenpickle.trace4cats.http4s.common.Http4sStatusMapping
  private[ziohttp] def toSpanStatus(s: Status): SpanStatus = s match {
    case Status.BadRequest          => SpanStatus.Internal("Bad Request")
    case Status.Unauthorized        => SpanStatus.Unauthenticated
    case Status.Forbidden           => SpanStatus.PermissionDenied
    case Status.NotFound            => SpanStatus.NotFound
    case Status.TooManyRequests     => SpanStatus.Unavailable
    case Status.BadGateway          => SpanStatus.Unavailable
    case Status.ServiceUnavailable  => SpanStatus.Unavailable
    case Status.GatewayTimeout      => SpanStatus.Unavailable
    case status if status.isSuccess => SpanStatus.Ok
    case _                          => SpanStatus.Unknown
  }
}
