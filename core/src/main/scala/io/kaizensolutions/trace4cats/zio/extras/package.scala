package io.kaizensolutions.trace4cats.zio

import cats.effect.kernel.Resource
import io.janstenpickle.trace4cats.ErrorHandler
import io.janstenpickle.trace4cats.inject.EntryPoint
import io.janstenpickle.trace4cats.model.{SpanKind, TraceHeaders}
import zio.*
import zio.interop.catz.*
import zio.stream.ZStream

package object extras {
  implicit class TaskEntryPointOps(entryPoint: Resource[Task, EntryPoint[Task]]) {
    def scoped: RIO[Scope, ZEntryPoint] =
      entryPoint.toScopedZIO
        .map(new ZEntryPoint(_))
  }

  implicit class RIOCBEntryPointOps[R](entryPoint: Resource[RIO[R, *], EntryPoint[Task]]) {
    def scoped: ZIO[R & Scope, Throwable, ZEntryPoint] =
      entryPoint.toScopedZIO
        .map(new ZEntryPoint(_))
  }

  implicit class ZTracerStreamOps[R, E, A](val s: ZStream[R, E, A]) extends AnyVal {
    def traceEachElement(
      extractName: A => String,
      kind: SpanKind = SpanKind.Internal,
      errorHandler: ErrorHandler = ErrorHandler.empty
    )(extractHeaders: A => TraceHeaders): ZStream[R & ZTracer, E, Spanned[A]] =
      ZStream
        .service[ZTracer]
        .flatMap(_.traceEachElement(extractHeaders, extractName, kind, errorHandler)(s))
  }

  implicit class ZTracerStreamSpannedOps[-R, +E, +A](val s: ZStream[R, E, Spanned[A]]) extends AnyVal {
    def mapThrough[B](f: A => B): ZStream[R, E, Spanned[B]] =
      s.map(_.map(f))

    def mapMTraced[R1 <: R, E1 >: E, B](name: String, kind: SpanKind = SpanKind.Internal)(
      f: A => ZIO[R1, E1, B]
    ): ZStream[R1 & ZTracer, E1, Spanned[B]] =
      s.mapZIO(_.mapZIOTraced(name, kind)(f))

    def mapMParTraced[R1 <: R, E1 >: E, B](name: String, kind: SpanKind = SpanKind.Internal)(n: Int)(
      f: A => ZIO[R1, E1, B]
    ): ZStream[R1 & ZTracer, E1, Spanned[B]] =
      s.mapZIOPar[R1 & ZTracer, E1, Spanned[B]](n)(_.mapZIOTraced(name, kind)(f))

    def mapMParUnorderedTraced[R1 <: R, E1 >: E, B](name: String, kind: SpanKind = SpanKind.Internal)(n: Int)(
      f: A => ZIO[R1, E1, B]
    ): ZStream[R1 & ZTracer, E1, Spanned[B]] =
      s.mapZIOParUnordered[R1 & ZTracer, E1, Spanned[B]](n)(_.mapZIOTraced(name, kind)(f))

    def endTracingEachElement: ZStream[R, E, (A, TraceHeaders)] =
      s.mapChunks(_.map(s => (s.value, s.headers)))
  }
}
