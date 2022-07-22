package io.kaizensolutions.trace4cats.zio.extras.fs2

import cats.effect.kernel.Resource.ExitCase
import fs2.*
import io.janstenpickle.trace4cats.model.{SpanKind, TraceHeaders}
import io.janstenpickle.trace4cats.{ErrorHandler, ToHeaders}
import io.kaizensolutions.trace4cats.zio.extras.{Spanned, ZTracer}
import zio.interop.catz.*
import zio.{RIO, ZIO}

object FS2Tracer {
  def traceEachElement[R, O](
    tracer: ZTracer,
    stream: Stream[RIO[R, *], O],
    extractName: O => String,
    kind: SpanKind,
    errorHandler: ErrorHandler
  )(extractHeaders: O => TraceHeaders): TracedStream[R, O] =
    stream.evalMapChunk(o =>
      tracer.fromHeaders[Any, Nothing, Spanned[O]](
        headers = extractHeaders(o),
        name = extractName(o),
        kind = kind,
        errorHandler = errorHandler
      )(span => ZIO.succeed(Spanned(span.extractHeaders(ToHeaders.all), o)))
    )

  def traceEntireStream[R, O](
    tracer: ZTracer,
    stream: Stream[RIO[R, *], O],
    name: String,
    kind: SpanKind = SpanKind.Internal,
    errorHandler: ErrorHandler = ErrorHandler.empty
  ): Stream[RIO[R, *], O] =
    Stream.force(
      tracer.withSpan(name, kind, errorHandler)(span =>
        ZIO.succeed(stream.onFinalizeCase {
          case ExitCase.Succeeded =>
            ZIO.unit

          case ExitCase.Errored(e) =>
            if (span.isSampled) span.put("fs2.stream.error.message", e.getLocalizedMessage)
            else ZIO.unit

          case ExitCase.Canceled =>
            if (span.isSampled) span.put("fs2.stream.cancelled", true)
            else ZIO.unit
        })
      )
    )
}
