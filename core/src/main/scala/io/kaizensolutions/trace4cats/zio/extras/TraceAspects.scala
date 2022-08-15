package io.kaizensolutions.trace4cats.zio.extras

import trace4cats.model.SpanKind
import zio.stream.{ZStream, ZStreamAspect}
import zio.{Trace, ZIO, ZIOAspect}

/**
 * Credits to Adam Fraser and Kit Langton's excellent talk on ZIO Aspects
 */
object TraceAspects {

  /**
   * A ZIO Aspect that allows you to trace the execution of a ZIO effect using
   * the line of the source code. For example:
   * {{{
   *   ZIO.attempt(println("Hello world!")) @@ tracedSource
   * }}}
   */
  val tracedSource: ZIOAspect[Nothing, ZTracer, Nothing, Any, Nothing, Any] =
    new ZIOAspect[Nothing, ZTracer, Nothing, Any, Nothing, Any] {
      override def apply[
        R >: Nothing <: ZTracer,
        E >: Nothing <: Any,
        A >: Nothing <: Any
      ](zio: ZIO[R, E, A])(implicit
        trace: Trace
      ): ZIO[R, E, A] = ZTracer.spanSource()(zio)
    }

  /**
   * A ZIO Aspect that allows you to trace the execution of a ZIO.. For example:
   * {{{
   *   ZIO.attempt(println("Hello world!")) @@ traced("saying-hello")
   * }}}
   */
  def traced(
    name: String,
    kind: SpanKind = SpanKind.Internal
  ): ZIOAspect[Nothing, ZTracer, Nothing, Any, Nothing, Any] =
    new ZIOAspect[Nothing, ZTracer, Nothing, Any, Nothing, Any] {
      override def apply[
        R >: Nothing <: ZTracer,
        E >: Nothing <: Any,
        A >: Nothing <: Any
      ](zio: ZIO[R, E, A])(implicit
        trace: Trace
      ): ZIO[R, E, A] = ZTracer.span(name, kind)(zio)
    }

  def traceEntireStream(
    name: String,
    kind: SpanKind = SpanKind.Internal
  ): ZStreamAspect[Nothing, ZTracer, Nothing, Any, Nothing, Any] =
    new ZStreamAspect[Nothing, ZTracer, Nothing, Any, Nothing, Any] {
      override def apply[
        R >: Nothing <: ZTracer,
        E >: Nothing <: Any,
        A >: Nothing <: Any
      ](stream: ZStream[R, E, A])(implicit
        trace: Trace
      ): ZStream[R, E, A] =
        ZTracer.traceEntireStream(name, kind)(stream)
    }
}
