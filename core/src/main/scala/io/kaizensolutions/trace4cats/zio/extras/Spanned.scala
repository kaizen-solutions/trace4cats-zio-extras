package io.kaizensolutions.trace4cats.zio.extras

import zio.{Has, ZIO}

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
final case class Spanned[+A](span: ZSpan, value: A) {
  def map[B](f: A => B): Spanned[B] =
    Spanned(span, f(value))

  def mapZIO[R, E, B](f: A => ZIO[R, E, B]): ZIO[R, E, Spanned[B]] =
    f(value).map(b => copy(value = b))

  def mapZIOTraced[R, E, B](f: A => ZIO[R, E, B]): ZIO[R & Has[ZTracer], E, Spanned[B]] =
    ZTracer.locally(span)(f(value).map(b => copy(value = b)))
}
