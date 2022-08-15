package io.kaizensolutions.trace4cats.zio.extras

import trace4cats.model.{SpanKind, TraceHeaders}
import zio.ZIO

/**
 * Attaches a span to an element. This is used to trace each element in a
 * ZStream.
 * @param span
 *   is the span that is attached to the element.
 * @param value
 *   is the element that is being traced.
 * @tparam A
 *   is the element type that is being traced.
 */
final case class Spanned[+A](headers: TraceHeaders, value: A) {
  def map[B](f: A => B): Spanned[B] =
    copy(value = f(value))

  def as[B](b: B): Spanned[B] =
    copy(value = b)

  def mapZIO[R, E, B](f: A => ZIO[R, E, B]): ZIO[R, E, Spanned[B]] =
    f(value).map(b => copy(value = b))

  def mapZIOTraced[R, E, B](name: String, kind: SpanKind = SpanKind.Internal)(
    f: A => ZIO[R, E, B]
  ): ZIO[R & ZTracer, E, Spanned[B]] =
    ZTracer.fromHeaders(headers, name, kind)(_ => f(value).map(b => copy(value = b)))

  def mapZIOWithTracer[R, E, B](tracer: ZTracer, name: String, kind: SpanKind = SpanKind.Internal)(
    f: A => ZIO[R, E, B]
  ): ZIO[R, E, Spanned[B]] =
    tracer.fromHeaders(headers, name, kind)(_ => f(value).map(b => copy(value = b)))
}
