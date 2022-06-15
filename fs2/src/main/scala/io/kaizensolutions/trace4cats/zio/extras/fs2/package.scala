package io.kaizensolutions.trace4cats.zio.extras
import _root_.fs2.*
import io.janstenpickle.trace4cats.model.{SpanKind, TraceHeaders}
import io.janstenpickle.trace4cats.{ErrorHandler, ToHeaders}
import zio.*
import zio.interop.catz.*

package object fs2 {
  // This type alias works around a variance error in Scala 2.x
  type TracedStream[-R <: ZTracer, +O] = Stream[RIO[R, *], Spanned[O]]

  implicit class Fs2ZTracerOps[-R <: ZTracer, +O](
    val stream: Stream[RIO[R, *], O]
  ) extends AnyVal {
    def traceEachElement[R1 <: R](
      name: String,
      kind: SpanKind = SpanKind.Internal,
      errorHandler: ErrorHandler = ErrorHandler.empty
    )(extractHeaders: O => TraceHeaders): TracedStream[R1, O] =
      stream
        .evalMapChunk(o =>
          ZIO.scoped {
            ZIO
              .serviceWithZIO[ZTracer](_.fromHeadersScoped(extractHeaders(o), name, kind, errorHandler))
              .map(Spanned(_, o))
          }
        )
  }

  implicit class Fs2ZTracerSpannedOps[R <: ZTracer, +O](
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
