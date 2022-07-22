package io.kaizensolutions.trace4cats.zio

import cats.effect.kernel.Resource
import io.janstenpickle.trace4cats.ErrorHandler
import io.janstenpickle.trace4cats.inject.EntryPoint
import io.janstenpickle.trace4cats.model.{SpanKind, TraceHeaders}
import zio.blocking.Blocking
import zio.clock.Clock
import zio.interop.catz.*
import zio.stream.ZStream
import zio.{Has, RIO, RManaged, Task, TaskManaged, ZIO}

package object extras {
  implicit class TaskEntryPointOps(entryPoint: Resource[Task, EntryPoint[Task]]) {
    def toZManaged: TaskManaged[ZEntryPoint] =
      entryPoint.toManagedZIO
        .map(new ZEntryPoint(_))
  }

  implicit class RIOCBEntryPointOps(entryPoint: Resource[RIO[Clock & Blocking, *], EntryPoint[Task]]) {
    def toZManaged: RManaged[Clock & Blocking, ZEntryPoint] =
      entryPoint.toManagedZIO
        .map(new ZEntryPoint(_))
  }

  implicit class ZTracerStreamOps[R <: Has[?], E, A](val s: ZStream[R, E, A]) extends AnyVal {
    def traceEachElement(
      extractName: A => String,
      kind: SpanKind = SpanKind.Internal,
      errorHandler: ErrorHandler = ErrorHandler.empty
    )(extractHeaders: A => TraceHeaders): ZStream[R & Has[ZTracer], E, Spanned[A]] =
      ZStream
        .service[ZTracer]
        .flatMap(_.traceEachElement(extractHeaders, extractName, kind, errorHandler)(s))
  }

  implicit class ZTracerStreamSpannedOps[-R <: Has[?], +E, +A](val s: ZStream[R, E, Spanned[A]]) extends AnyVal {
    def mapThrough[B](f: A => B): ZStream[R, E, Spanned[B]] =
      s.map(_.map(f))

    def mapMTraced[R1 <: R, E1 >: E, B](name: String, kind: SpanKind = SpanKind.Internal)(
      f: A => ZIO[R1, E1, B]
    ): ZStream[R1 & Has[ZTracer], E1, Spanned[B]] =
      s.mapM(_.mapZIOTraced(name, kind)(f))

    def mapMParTraced[R1 <: R, E1 >: E, B](name: String, kind: SpanKind = SpanKind.Internal)(n: Int)(
      f: A => ZIO[R1, E1, B]
    ): ZStream[R1 & Has[ZTracer], E1, Spanned[B]] =
      s.mapMPar[R1 & Has[ZTracer], E1, Spanned[B]](n)(_.mapZIOTraced(name, kind)(f))

    def mapMParUnorderedTraced[R1 <: R, E1 >: E, B](name: String, kind: SpanKind = SpanKind.Internal)(n: Int)(
      f: A => ZIO[R1, E1, B]
    ): ZStream[R1 & Has[ZTracer], E1, Spanned[B]] =
      s.mapMParUnordered[R1 & Has[ZTracer], E1, Spanned[B]](n)(_.mapZIOTraced(name, kind)(f))

    def endTracingEachElement: ZStream[R & Has[ZTracer], E, (A, TraceHeaders)] = {
      ZStream
        .service[ZTracer]
        .flatMap(_.endTracingEachElement(s))
    }
  }
}
