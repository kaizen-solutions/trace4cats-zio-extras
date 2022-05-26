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
      case Some(span) => span.extractHeaders(headerTypes)
      case None       => headerTypes.fromContext(SpanContext.invalid)
    }

  /**
   * Allows you to obtain a span from the trace headers
   *
   * @param headers
   *   are trace headers coming from your upstream services that you are
   *   integrating with
   * @param kind
   *   is the kind of span you are creating (Client/Server/Internal)
   * @param name
   *   is the name of the span you are creating
   * @param errorHandler
   *   is the error handler to use when an error occurs with the span
   * @param fn
   *   is the function that will be executed within the context of the span
   * @tparam R
   *   is the ZIO environment type
   * @tparam E
   *   is the ZIO error type
   * @tparam A
   *   is the ZIO success type
   * @return
   *   the result of the function along with sending the tracing results
   *   transparently
   */
  def fromHeaders[R, E, A](
    headers: TraceHeaders,
    kind: SpanKind = SpanKind.Internal,
    name: String = "root",
    errorHandler: ErrorHandler = ErrorHandler.empty
  )(fn: ZSpan => ZIO[R, E, A]): ZIO[R, E, A] =
    fromHeadersManaged(headers, kind, name, errorHandler)
      .use(child => current.locally(Some(child))(fn(child)))

  /**
   * Allows you to obtain a ZSpan from trace headers This is a low level
   * operator and you are responsible for manipulating the current span
   * (updateCurrentSpan and removeCurrentSpan). For example, we would recommend
   * doing the following:
   *
   * {{{
   * fromHeadersManaged(yourHeaders)      // produces a ZSpan
   *   .tapM(updateCurrentSpan)           // sets the current span to the span we just produced
   *   .onExit(_ => removeCurrentSpan)    // removes the current span when the resource finaliztion takes place
   * }}}
   *
   * @param headers
   * @param kind
   * @param name
   * @param errorHandler
   * @return
   */
  def fromHeadersManaged(
    headers: TraceHeaders,
    kind: SpanKind = SpanKind.Internal,
    name: String = "root",
    errorHandler: ErrorHandler = ErrorHandler.empty
  ): UManaged[ZSpan] =
    entryPoint.fromHeadersOtherwiseRoot(headers, kind, name, errorHandler)

  def put(key: String, value: AttributeValue): UIO[Unit] =
    current.get.flatMap {
      case None       => UIO.unit
      case Some(span) => span.put(key, value)
    }

  def putAll(fields: (String, AttributeValue)*): UIO[Unit] =
    current.get.flatMap {
      case None       => UIO.unit
      case Some(span) => span.putAll(fields*)
    }

  def spanSource[R, E, A](
    kind: SpanKind = SpanKind.Internal
  )(zio: ZIO[R, E, A])(implicit fileName: sourcecode.FileName, line: sourcecode.Line): ZIO[R, E, A] =
    spanManaged(s"${fileName.value}:${line.value}", kind).use(span => current.locally(Some(span))(zio))

  def span[R, E, A](
    name: String,
    kind: SpanKind = SpanKind.Internal,
    errorHandler: ErrorHandler = ErrorHandler.empty
  )(zio: ZIO[R, E, A]): ZIO[R, E, A] =
    spanManaged(name, kind, errorHandler).use(span => current.locally(Some(span))(zio))

  /**
   * This is a low level operator and you are responsible for manipulating the
   * current span (updateCurrentSpan and removeCurrentSpan). For example, we
   * would recommend doing the following:
   *
   * {{{
   * spanManaged("mySpan")                // produces a ZSpan
   *   .tapM(updateCurrentSpan)           // sets the current span to the span we just produced
   *   .onExit(_ => removeCurrentSpan)    // removes the current span when the resource finalization takes place
   * }}}
   *
   * @param name
   *   is the name of the span
   * @param kind
   *   is the kind of span
   * @param errorHandler
   *   is the error handler for the span
   * @return
   */
  def spanManaged(
    name: String,
    kind: SpanKind = SpanKind.Internal,
    errorHandler: ErrorHandler = ErrorHandler.empty
  ): UManaged[ZSpan] =
    current.get.toManaged_.flatMap {
      case Some(span) => span.child(name, kind, errorHandler)
      case None       => entryPoint.rootSpan(name, kind, errorHandler)
    }

  val removeCurrentSpan: UIO[Unit] =
    current.set(None)

  def updateCurrentSpan(in: ZSpan): UIO[Unit] =
    current.set(Some(in))

  def withSpan[R, E, A](
    name: String,
    kind: SpanKind = SpanKind.Internal,
    errorHandler: ErrorHandler = ErrorHandler.empty
  )(fn: ZSpan => ZIO[R, E, A]): ZIO[R, E, A] =
    spanManaged(name, kind, errorHandler).use(span => current.locally(Some(span))(fn(span)))
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

  def withSpan[R <: Has[?], E, A](
    name: String,
    kind: SpanKind = SpanKind.Internal,
    errorHandler: ErrorHandler = ErrorHandler.empty
  )(fn: ZSpan => ZIO[R, E, A]): ZIO[R & Has[ZTracer], E, A] =
    ZIO.service[ZTracer].flatMap(_.withSpan(name, kind, errorHandler)(fn))

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
