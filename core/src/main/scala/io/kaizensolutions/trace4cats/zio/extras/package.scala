package io.kaizensolutions.trace4cats.zio

import cats.effect.kernel.Resource
import io.janstenpickle.trace4cats.inject.EntryPoint
import io.janstenpickle.trace4cats.model.{SpanKind, TraceHeaders}
import io.janstenpickle.trace4cats.{ErrorHandler, ToHeaders}
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
      name: String,
      kind: SpanKind = SpanKind.Internal,
      errorHandler: ErrorHandler = ErrorHandler.empty
    )(extractHeaders: A => TraceHeaders): ZStream[R & Has[ZTracer] & Has[StreamElementTracer], E, A] =
      ZStream
        .service[ZTracer]
        .flatMap(_.traceEachElement(extractHeaders, name, kind, errorHandler)(s))
  }

  implicit class ZTracerStreamSpannedOps[-R <: Has[StreamElementTracer], +E, +A](
    val s: ZStream[R, E, A]
  ) extends AnyVal {
    def mapMTraced[R1 <: R, E1 >: E, B](
      f: A => ZIO[R1, E1, B]
    ): ZStream[R1 & Has[ZTracer] & Has[StreamElementTracer], E1, B] =
      s.mapM(a =>
        for {
          elementSpan <- StreamElementTracer.get
          b           <- ZTracer.locally(elementSpan)(f(a))
        } yield b
      )

    def mapMParTraced[R1 <: R, E1 >: E, B](n: Int)(
      f: A => ZIO[R1, E1, B]
    ): ZStream[R1 & Has[ZTracer] & Has[StreamElementTracer], E1, B] =
      s.mapMPar[R1 & Has[ZTracer] & Has[StreamElementTracer], E1, B](n)(a =>
        for {
          elementSpan <- StreamElementTracer.get
          b           <- ZTracer.locally(elementSpan)(f(a))
        } yield b
      )

    def mapMParUnorderedTraced[R1 <: R, E1 >: E, B](n: Int)(
      f: A => ZIO[R1, E1, B]
    ): ZStream[R1 & Has[ZTracer], E1, B] =
      s.mapMParUnordered[R1 & Has[ZTracer] & Has[StreamElementTracer], E1, B](n)(a =>
        for {
          elementSpan <- StreamElementTracer.get
          b           <- ZTracer.locally(elementSpan)(f(a))
        } yield b
      )

    def endTracingEachElement(
      headers: ToHeaders
    ): ZStream[R & Has[ZTracer], E, (A, TraceHeaders)] = {
      ZStream
        .service[ZTracer]
        .flatMap(_.endTracingEachElement(s, headers))
    }

    def endTracingEachElement: ZStream[R & Has[ZTracer], E, (A, TraceHeaders)] = {
      ZStream
        .service[ZTracer]
        .flatMap(_.endTracingEachElement(s))
    }
  }
}
