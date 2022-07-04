package io.kaizensolutions.trace4cats.zio.extras.fs2

import fs2.*
import io.janstenpickle.trace4cats.ErrorHandler
import io.janstenpickle.trace4cats.model.{SpanKind, TraceHeaders}
import io.kaizensolutions.trace4cats.zio.extras.{Spanned, ZTracer}
import zio.interop.catz.*
import zio.{RIO, RManaged}

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
}
