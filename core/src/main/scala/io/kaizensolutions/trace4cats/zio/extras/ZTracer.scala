package io.kaizensolutions.trace4cats.zio.extras

import io.janstenpickle.trace4cats.model.{AttributeValue, SpanContext, SpanKind, TraceHeaders}
import io.janstenpickle.trace4cats.{ErrorHandler, ToHeaders}
import zio.*

/**
 * ZTracer is a ZIO wrapper around the Trace4Cats Span. The abstraction utilizes
 * a FiberRef to track and update the current span as the user opens and closes
 * spans
 * @param current
 *   is the current span
 * @param entryPoint
 *   is the entrypoint into the tracing system
 */
final case class ZTracer private (
  private val current: FiberRef[Option[ZSpan]],
  private[extras] val entryPoint: ZEntryPoint
) { self =>
  def context: UIO[SpanContext] =
    current.get.map(_.fold(SpanContext.invalid)(_.context))

  def extractHeaders(headerTypes: ToHeaders = ToHeaders.all): UIO[TraceHeaders] =
    current.get.map {
      case Some(span) => headerTypes.fromContext(span.context)
      case None       => headerTypes.fromContext(SpanContext.invalid)
    }

  def fromHeaders[R, E, A](
    headers: TraceHeaders,
    kind: SpanKind = SpanKind.Internal,
    nameWhenMissingHeaders: String = "root",
    errorHandler: ErrorHandler = ErrorHandler.empty
  )(fn: ZSpan => ZIO[R, E, A]): ZIO[R, E, A] =
    entryPoint
      .fromHeadersOtherwiseRoot(headers, kind, nameWhenMissingHeaders, errorHandler)
      .use(child => current.locally(Some(child))(fn(child)))

  def put(key: String, value: AttributeValue): UIO[Unit] =
    current.get.flatMap {
      case None       => UIO.unit
      case Some(span) => span.put(key, value)
    }

  def putAll(fields: (String, AttributeValue)*): UIO[Unit] =
    current.get.flatMap {
      case None       => UIO.unit
      case Some(span) => span.putAll(fields *)
    }

  def spanSource[R, E, A](
    kind: SpanKind = SpanKind.Internal
  )(zio: ZIO[R, E, A])(implicit fileName: sourcecode.FileName, line: sourcecode.Line): ZIO[R, E, A] =
    current.get.toManaged_.flatMap {
      case Some(span) => span.child(kind)
      case None       => entryPoint.rootSpan(s"${fileName.value}:${line.value}", kind)
    }.use(span => current.locally(Some(span))(zio))

  def span[R, E, A](
    name: String,
    kind: SpanKind = SpanKind.Internal,
    errorHandler: ErrorHandler = ErrorHandler.empty
  )(zio: ZIO[R, E, A]): ZIO[R, E, A] =
    current.get.toManaged_.flatMap {
      case Some(span) => span.child(name, kind, errorHandler)
      case None       => entryPoint.rootSpan(name, kind, errorHandler)
    }.use(span => current.locally(Some(span))(zio))

  def withSpan[R, E, A](
    name: String,
    kind: SpanKind = SpanKind.Internal,
    errorHandler: ErrorHandler = ErrorHandler.empty
  )(fn: ZSpan => ZIO[R, E, A]): ZIO[R, E, A] =
    current.get.toManaged_.flatMap {
      case Some(span) => span.child(name, kind, errorHandler)
      case None       => entryPoint.rootSpan(name, kind, errorHandler)
    }.use(span => current.locally(Some(span))(fn(span)))
}
object ZTracer {
  def make(current: FiberRef[Option[ZSpan]], entryPoint: ZEntryPoint): ZTracer =
    new ZTracer(current, entryPoint)

  def span[R <: Has[?], E, A](
    name: String,
    kind: SpanKind = SpanKind.Internal,
    errorHandler: ErrorHandler = ErrorHandler.empty
  )(zio: ZIO[R, E, A]): ZIO[R & Has[ZTracer], E, A] =
    ZIO.service[ZTracer].flatMap(_.span(name, kind, errorHandler)(zio))

  def spanSource[R <: Has[?], E, A](
    kind: SpanKind = SpanKind.Internal
  )(zio: ZIO[R, E, A])(implicit fileName: sourcecode.FileName, line: sourcecode.Line): ZIO[R & Has[ZTracer], E, A] =
    ZIO.service[ZTracer].flatMap(_.spanSource(kind)(zio)(fileName, line))

  val live: URLayer[Has[ZEntryPoint], Has[ZTracer]] =
    ZLayer.fromServiceM[ZEntryPoint, Any, Nothing, ZTracer](ep =>
      FiberRef
        .make[Option[ZSpan]](None)
        .map(spanRef => ZTracer.make(spanRef, ep))
    )
}
