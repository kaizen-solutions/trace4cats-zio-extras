package io.kaizensolutions.trace4cats.zio.extras

import io.janstenpickle.trace4cats.model.{AttributeValue, SpanContext, SpanKind, TraceHeaders}
import io.janstenpickle.trace4cats.{ErrorHandler, ToHeaders}
import zio._

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
  private val entryPoint: ZEntryPoint
) {
  def context: UIO[SpanContext] =
    current.get.map(_.fold(SpanContext.invalid)(_.context))

  def extractHeaders(headerTypes: ToHeaders = ToHeaders.all): UIO[TraceHeaders] =
    current.get.map {
      case Some(span) => headerTypes.fromContext(span.context)
      case None       => headerTypes.fromContext(SpanContext.invalid)
    }

  def put(key: String, value: AttributeValue): UIO[Unit] =
    current.get.flatMap {
      case None       => UIO.unit
      case Some(span) => span.put(key, value)
    }

  def putAll(fields: (String, AttributeValue)*): UIO[Unit] =
    current.get.flatMap {
      case None       => UIO.unit
      case Some(span) => span.putAll(fields: _*)
    }

  def spanSource[R, E, A](
    kind: SpanKind = SpanKind.Internal
  )(zio: ZIO[R, E, A])(implicit file: sourcecode.File, line: sourcecode.Line): ZIO[R, E, A] =
    current.get.toManaged_.flatMap {
      case Some(span) => span.child(kind)
      case None       => entryPoint.rootSpan(s"${file.value}:${line.value}", kind)
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
  val live: URLayer[Has[ZEntryPoint], Has[ZTracer]] =
    ZLayer.fromServiceM[ZEntryPoint, Any, Nothing, ZTracer](ep =>
      FiberRef
        .make[Option[ZSpan]](None)
        .map(spanRef => ZTracer(spanRef, ep))
    )

  def fromHeaders(
    headers: TraceHeaders,
    kind: SpanKind = SpanKind.Internal,
    nameWhenMissingHeaders: String = "root"
  ): URManaged[Has[ZTracer], ZTracer] =
    ZManaged.serviceWithManaged[ZTracer](tracer =>
      tracer.entryPoint
        .fromHeadersOtherwiseRoot(headers, kind, nameWhenMissingHeaders)
        .mapM(span => FiberRef.make(Option(span)))
        .map(spanRef => tracer.copy(current = spanRef))
    )
}
