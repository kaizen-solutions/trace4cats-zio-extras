package io.kaizensolutions.trace4cats.zio.extras

import zio.{Has, UIO, ZIO}

/**
 * Attaches a span to an element. This is used to trace each element in a
 * ZStream.
 * @param span
 *   is the span that is attached to the element.
 * @param value
 *   is the element that is being traced.
 *
 * @param closeSpan
 *   is the handle to close the outer span
 *
 * @tparam A
 *   is the element type that is being traced.
 */
final case class Spanned[+A](span: ZSpan, closeSpan: UIO[Any], value: A) {
  def map[B](f: A => B): Spanned[B] =
    copy(value = f(value))

  def mapZIO[R, E, B](f: A => ZIO[R, E, B]): ZIO[R, E, Spanned[B]] =
    f(value).map(b => copy(value = b))

  def mapZIOTraced[R, E, B](f: A => ZIO[R, E, B]): ZIO[R & Has[ZTracer], E, Spanned[B]] =
    ZTracer.locally(span)(f(value).map(b => copy(value = b)))

  def mapZIOTraced[R, E, B](tracer: ZTracer)(f: A => ZIO[R, E, B]): ZIO[R, E, Spanned[B]] =
    tracer.locally(span)(f(value).map(b => copy(value = b)))
}
