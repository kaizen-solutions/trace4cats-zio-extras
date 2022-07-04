package io.kaizensolutions.trace4cats.zio.extras.fs2

import fs2.*
import io.janstenpickle.trace4cats.ErrorHandler
import io.janstenpickle.trace4cats.model.{SpanKind, TraceHeaders}
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
    stream
      .evalMapChunk(o =>
        ZIO.scoped[Any] {
          tracer
            .fromHeadersScoped(extractHeaders(o), extractName(o), kind, errorHandler)
            .map(Spanned(_, o))
        }
      )
}
