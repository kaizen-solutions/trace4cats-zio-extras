package io.kaizensolutions.trace4cats.zio.extras
import _root_.fs2.*
import io.janstenpickle.trace4cats.{ErrorHandler, ToHeaders}
import io.janstenpickle.trace4cats.model.{SpanKind, TraceHeaders}
import zio.blocking.Blocking
import zio.clock.Clock
import zio.interop.catz.*
import zio.{Has, RIO, RManaged, ZManaged}

package object fs2 {
  // This type alias works around a variance error in Scala 2.x
  type TracedStream[-R <: Clock & Blocking & Has[ZTracer], +O] = Stream[RIO[R, *], Spanned[O]]

  implicit class Fs2ZTracerOps[-R <: Clock & Blocking & Has[ZTracer], +O](
    val stream: Stream[RIO[R, *], O]
  ) extends AnyVal {
    def traceEachElement[R1 <: R](
      name: String,
      kind: SpanKind = SpanKind.Internal,
      errorHandler: ErrorHandler = ErrorHandler.empty
    )(extractHeaders: O => TraceHeaders): TracedStream[R1, O] =
      traceEachElement[R1]((_: O) => name, kind, errorHandler)(extractHeaders)

    def traceEachElement[R1 <: R](
      extractName: O => String,
      kind: SpanKind,
      errorHandler: ErrorHandler
    )(extractHeaders: O => TraceHeaders): TracedStream[R1, O] =
      stream.chunks
        .flatMap(chunk =>
          Stream.resource(
            ZManaged
              .service[ZTracer]
              .flatMap(tracer =>
                chunk.traverse[RManaged[R1, *], Spanned[O]](o =>
                  tracer.fromHeadersManaged(extractHeaders(o), extractName(o), kind, errorHandler).map(Spanned(_, o))
                )
              )
              .toResourceZIO
          )
        )
        .unchunks
  }

  implicit class Fs2ZTracerSpannedOps[R <: Clock & Blocking & Has[ZTracer], +O](
    val stream: TracedStream[R, O]
  ) extends AnyVal {
    def mapThrough[O1](f: O => O1): TracedStream[R, O1] = stream.map(_.map(f))

    def evalMapTraced[O1](f: O => RIO[R, O1]): TracedStream[R, O1] =
      stream.evalMap(_.mapZIOTraced(f))

    def parEvalMapUnboundedTraced[O1](f: O => RIO[R, O1]): TracedStream[R, O1] =
      stream.parEvalMapUnbounded(_.mapZIOTraced(f))

    def parEvalMapTraced[O1](n: Int)(f: O => RIO[R, O1]): TracedStream[R, O1] =
      stream.parEvalMap[RIO[R, *], Spanned[O1]](n)(_.mapZIOTraced[R, Throwable, O1](f))

    def parEvalMapUnorderedTraced[O1](n: Int)(f: O => RIO[R, O1]): TracedStream[R, O1] =
      stream.parEvalMapUnordered[RIO[R, *], Spanned[O1]](n)(_.mapZIOTraced[R, Throwable, O1](f))

    def endTracingEachElement(headers: ToHeaders = ToHeaders.standard): Stream[RIO[R, *], (O, TraceHeaders)] =
      stream.mapChunks(_.map(s => (s.value, headers.fromContext(s.span.context))))
  }
}
