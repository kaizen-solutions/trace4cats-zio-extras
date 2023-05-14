package io.kaizensolutions.trace4cats.zio.extras

import cats.data.NonEmptyList
import trace4cats.model.*
import trace4cats.{ErrorHandler, Span, ToHeaders}
import zio.interop.catz.*
import zio.{NonEmptyChunk, Scope, Task, UIO, URIO}

/**
 * Newtype on the underlying Trace4Cats Span but with a less powerful API. This
 * is a supporting type for the ZTracer abstraction.
 *
 * @param underlying
 *   is the underlying Trace4Cats Span
 */
final class ZSpan(private val underlying: Span[Task]) extends AnyVal {
  def isSampled: Boolean = context.traceFlags.sampled == SampleDecision.Include

  def context: SpanContext = underlying.context

  def put(key: String, value: AttributeValue): UIO[Unit] = underlying.put(key, value).ignore

  def putAll(fields: (String, AttributeValue)*): UIO[Unit] = underlying.putAll(fields.toMap).ignore

  def putAll(fields: Map[String, AttributeValue]): UIO[Unit] = underlying.putAll(fields).ignore

  def setStatus(spanStatus: SpanStatus): UIO[Unit] = underlying.setStatus(spanStatus).ignore

  def addLink(link: Link): UIO[Unit] = underlying.addLink(link).ignore

  def addLinks(links: NonEmptyChunk[Link]): UIO[Unit] =
    underlying.addLinks(NonEmptyList.of(links.head, links.tail: _*)).ignore

  def extractHeaders(headerTypes: ToHeaders): TraceHeaders =
    headerTypes.fromContext(underlying.context)

  private[extras] def child(implicit fileName: sourcecode.FileName, line: sourcecode.Line): URIO[Scope, ZSpan] =
    child(s"${fileName.value}:${line.value}", SpanKind.Internal)

  private[extras] def child(
    kind: SpanKind
  )(implicit fileName: sourcecode.FileName, line: sourcecode.Line): URIO[Scope, ZSpan] =
    child(s"${fileName.value}:${line.value}", kind)

  private[extras] def child(name: String, kind: SpanKind): URIO[Scope, ZSpan] =
    underlying.child(name, kind).map(new ZSpan(_)).toScopedZIO.orDie

  private[extras] def child(name: String, kind: SpanKind, errorHandler: ErrorHandler): URIO[Scope, ZSpan] =
    underlying.child(name, kind, errorHandler).map(new ZSpan(_)).toScopedZIO.orDie
}
object ZSpan {
  def make(underlying: Span[Task]): ZSpan = new ZSpan(underlying)
  def noop: ZSpan                         = new ZSpan(Span.noopInstance[Task])
}
