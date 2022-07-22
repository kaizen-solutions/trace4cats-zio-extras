package io.kaizensolutions.trace4cats.zio.extras
import _root_.fs2.*
import io.janstenpickle.trace4cats.ErrorHandler
import io.janstenpickle.trace4cats.model.{SpanKind, TraceHeaders}
import zio.interop.catz.*
import zio.{Has, RIO, ZIO}

package object fs2 {
  // This type alias works around a variance error in Scala 2.x
  type TracedStream[-R, +O] = Stream[RIO[R, *], Spanned[O]]

  implicit class Fs2ZTracerOps[-R <: Has[ZTracer], +O](
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
      Stream
        .eval(ZIO.service[ZTracer])
        .flatMap(tracer => FS2Tracer.traceEachElement(tracer, stream, extractName, kind, errorHandler)(extractHeaders))

    def traceEntireStream[R1 <: R](
      name: String,
      kind: SpanKind = SpanKind.Internal,
      errorHandler: ErrorHandler = ErrorHandler.empty
    ): Stream[RIO[R1, *], O] =
      Stream
        .eval(ZIO.service[ZTracer])
        .flatMap(tracer => FS2Tracer.traceEntireStream(tracer, stream, name, kind, errorHandler))
  }

  implicit class Fs2ZTracerSpannedOps[R <: Has[ZTracer], +O](
    val stream: TracedStream[R, O]
  ) extends AnyVal {
    def evalMapTraced[O1](name: String, kind: SpanKind = SpanKind.Internal)(f: O => RIO[R, O1]): TracedStream[R, O1] =
      stream.evalMap(_.mapZIOTraced(name, kind)(f))

    def parEvalMapUnboundedTraced[O1](name: String, kind: SpanKind = SpanKind.Internal)(
      f: O => RIO[R, O1]
    ): TracedStream[R, O1] =
      stream.parEvalMapUnbounded(_.mapZIOTraced(name, kind)(f))

    def parEvalMapTraced[O1](name: String, kind: SpanKind = SpanKind.Internal)(n: Int)(
      f: O => RIO[R, O1]
    ): TracedStream[R, O1] =
      stream.parEvalMap[RIO[R, *], Spanned[O1]](n)(_.mapZIOTraced[R, Throwable, O1](name, kind)(f))

    def parEvalMapUnorderedTraced[O1](name: String, kind: SpanKind = SpanKind.Internal)(n: Int)(
      f: O => RIO[R, O1]
    ): TracedStream[R, O1] =
      stream.parEvalMapUnordered[RIO[R, *], Spanned[O1]](n)(_.mapZIOTraced[R, Throwable, O1](name, kind)(f))
  }

  implicit class Fs2SpannedOps[R, +O](val stream: TracedStream[R, O]) extends AnyVal {
    def mapThrough[O1](f: O => O1): TracedStream[R, O1] = stream.map(_.map(f))

    def evalMapWithTracer[O1](tracer: ZTracer, name: String, kind: SpanKind = SpanKind.Internal)(
      f: O => RIO[R, O1]
    ): TracedStream[R, O1] =
      stream.evalMap(_.mapZIOWithTracer(tracer, name, kind)(f))

    def parEvalMapUnboundedWithTracer[O1](tracer: ZTracer, name: String, kind: SpanKind = SpanKind.Internal)(
      f: O => RIO[R, O1]
    ): TracedStream[R, O1] =
      stream.parEvalMapUnbounded(_.mapZIOWithTracer(tracer, name, kind)(f))

    def parEvalMapWithTracer[O1](tracer: ZTracer, name: String, kind: SpanKind = SpanKind.Internal)(n: Int)(
      f: O => RIO[R, O1]
    ): TracedStream[R, O1] =
      stream.parEvalMap[RIO[R, *], Spanned[O1]](n)(_.mapZIOWithTracer[R, Throwable, O1](tracer, name, kind)(f))

    def parEvalMapUnorderedWithTracer[O1](tracer: ZTracer, name: String, kind: SpanKind = SpanKind.Internal)(n: Int)(
      f: O => RIO[R, O1]
    ): TracedStream[R, O1] =
      stream.parEvalMapUnordered[RIO[R, *], Spanned[O1]](n)(_.mapZIOWithTracer[R, Throwable, O1](tracer, name, kind)(f))

    def endTracingEachElement: Stream[RIO[R, *], (O, TraceHeaders)] =
      stream.mapChunks(_.map(s => (s.value, s.headers)))
  }
}
