package io.kaizensolutions.trace4cats.zio.extras.fs2

import cats.effect.kernel.Resource.ExitCase
import fs2.*
import io.janstenpickle.trace4cats.ErrorHandler
import io.janstenpickle.trace4cats.model.{SpanKind, TraceHeaders}
import io.kaizensolutions.trace4cats.zio.extras.{Spanned, ZTracer}
import zio.interop.catz.*
import zio.{RIO, RManaged, ZIO}

object FS2Tracer {
  def traceEachElement[R, O](
    tracer: ZTracer,
    stream: Stream[RIO[R, *], O],
    extractName: O => String,
    kind: SpanKind,
    errorHandler: ErrorHandler
  )(extractHeaders: O => TraceHeaders): TracedStream[R, O] =
    stream.chunks
      .flatMap(chunk =>
        Stream.resource(
          chunk
            .traverse[RManaged[R, *], Spanned[O]](o =>
              tracer.fromHeadersManaged(extractHeaders(o), extractName(o), kind, errorHandler).map(Spanned(_, o))
            )
            .toResourceZIO
        )(concurrentInstance[R, Throwable]) // Scala 3.1.x workaround
      )
      .unchunks

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
