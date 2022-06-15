package io.kaizensolutions.trace4cats.zio.extras

import io.janstenpickle.trace4cats.model.{AttributeValue, SpanContext, SpanKind, TraceHeaders}
import io.janstenpickle.trace4cats.{ErrorHandler, ToHeaders}
import zio.*
import zio.stream.ZStream

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
    ZIO.scoped[R](
      fromHeadersScoped(headers, name, kind, errorHandler)
        .flatMap(child => current.locally(Some(child))(fn(child)))
    )

  /**
   * Allows you to obtain a ZSpan from trace headers This is a low level
   * operator and you are responsible for manipulating the current span
   * (updateCurrentSpan and removeCurrentSpan). For example, we would recommend
   * doing the following:
   *
   * {{{
   * fromHeadersScoped(yourHeaders)         // produces a ZSpan
   *   .tap(updateCurrentSpan)          // sets the current span to the span we just produced
   *   .onExit(_ => removeCurrentSpan)      // removes the current span when the finalization takes place
   * }}}
   *
   * @param headers
   * @param kind
   * @param name
   * @param errorHandler
   * @return
   */
  def fromHeadersScoped(
    headers: TraceHeaders,
    name: String = "root",
    kind: SpanKind = SpanKind.Internal,
    errorHandler: ErrorHandler = ErrorHandler.empty
  ): URIO[Scope, ZSpan] =
    entryPoint.fromHeadersOtherwiseRoot(headers, kind, name, errorHandler)

  def put(key: String, value: AttributeValue): UIO[Unit] =
    current.get.flatMap {
      case None       => ZIO.unit
      case Some(span) => span.put(key, value)
    }

  def putAll(fields: (String, AttributeValue)*): UIO[Unit] =
    current.get.flatMap {
      case None       => ZIO.unit
      case Some(span) => span.putAll(fields*)
    }

  def spanSource[R, E, A](
    kind: SpanKind = SpanKind.Internal
  )(zio: ZIO[R, E, A])(implicit fileName: sourcecode.FileName, line: sourcecode.Line): ZIO[R, E, A] = {
    ZIO.scoped[R] {
      spanScoped(s"${fileName.value}:${line.value}", kind)
        .flatMap(span => current.locally(Some(span))(zio))
    }
  }

  def span[R, E, A](
    name: String,
    kind: SpanKind = SpanKind.Internal,
    errorHandler: ErrorHandler = ErrorHandler.empty
  )(zio: ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.scoped[R] {
      spanScoped(name, kind, errorHandler).flatMap(span => current.locally(Some(span))(zio))
    }

  /**
   * This is a low level operator and leaves you, the user, to manipulate the
   * current span using [[updateCurrentSpan]] and [[removeCurrentSpan]] or
   * [[restore]]. We recommend using [[spanScoped]] instead.
   *
   * For example:
   *
   * {{{
   * spanScopedManual("mySpan")           // produces a ZSpan
   *   .tapM(updateCurrentSpan)           // sets the current span to the span we just produced
   *   .onExit(_ => removeCurrentSpan)    // removes the current span when the resource finalization takes place
   * }}}
   *
   * WARNING: Please note that the above is just an example, rather than
   * removing the current span (i.e. setting the FiberRef to None, you should be
   * restoring it to what was there previously before you called span). You
   * should use [[spanScoped]] instead.
   *
   * @param name
   *   is the name of the span
   * @param kind
   *   is the kind of span
   * @param errorHandler
   *   is the error handler for the span
   * @return
   */
  def spanScopedManual(
    name: String,
    kind: SpanKind = SpanKind.Internal,
    errorHandler: ErrorHandler = ErrorHandler.empty
  ): URIO[Scope, ZSpan] =
    current.get.flatMap {
      case Some(span) => span.child(name, kind, errorHandler)
      case None       => entryPoint.rootSpan(name, kind, errorHandler)
    }

  /**
   * Works like [[withSpan]] but in the context of a [[zio.Scope]] and handles
   * updating of the underlying Span context automatically for you.
   * @param name
   *   is the name of the span
   * @param kind
   *   is the kind of the span
   * @param errorHandler
   *   is the error handler to use in case the span fails
   * @return
   */
  def spanScoped(
    name: String,
    kind: SpanKind = SpanKind.Internal,
    errorHandler: ErrorHandler = ErrorHandler.empty
  ): URIO[Scope, ZSpan] =
    retrieveCurrentSpan
      .flatMap(current =>
        spanScopedManual(name, kind, errorHandler)
          .tap(updateCurrentSpan)
          .ensuring(restore(current))
      )

  /**
   * This operator is used to trace each element in a ZStream. Each element
   * needs to provide enough context so we can extract tracer headers and turn
   * them into spans which the ZTracer mechanism can use. For example, Kafka
   * messages can place this Trace Header information in the Kafka message
   * headers and we can hold onto it and use it to continue the trace across
   * boundaries
   *
   * @param extractHeaders
   *   is a function that extracts the trace headers from the element
   * @param name
   *   is the name of the span
   * @param kind
   *   is the kind of span
   * @param errorHandler
   *   is the error handler for the span
   * @param stream
   *   is the stream's elements that will be traced
   * @tparam R
   *   is the environment type
   * @tparam E
   *   is the error type
   * @tparam O
   *   is the output element type
   * @return
   */
  def traceEachElement[R, E, O](
    extractHeaders: O => TraceHeaders,
    name: String,
    kind: SpanKind = SpanKind.Internal,
    errorHandler: ErrorHandler = ErrorHandler.empty
  )(stream: ZStream[R, E, O]): ZStream[R, E, Spanned[O]] =
    stream
      .mapChunksZIO(inputs =>
        ZIO.scoped[R](
          ZIO.foreach(inputs)(input =>
            fromHeadersScoped(extractHeaders(input), name, kind, errorHandler)
              .map(Spanned(_, input))
          )
        )
      )

  /**
   * End tracing each element of the Stream
   *
   * @param stream
   *   is the stream whose elements are of type `Spanned[A]` which we wish to
   *   stop spanning
   * @param headers
   *   is the headers to extract from each span
   * @tparam R
   *   is the environment
   * @tparam E
   *   is the error type
   * @tparam O
   *   is the output element type that was originally spanned
   * @return
   */
  def endTracingEachElement[R, E, O](
    stream: ZStream[R, E, Spanned[O]],
    headers: ToHeaders = ToHeaders.standard
  ): ZStream[R, E, (O, TraceHeaders)] =
    stream.mapChunks(_.map(s => (s.value, headers.fromContext(s.span.context))))

  /**
   * This is a low level operator that can potentially be used with
   * [[spanScopedManual]] but using `retrieveCurrentSpan` and a finalizer
   * calling `updateCurrentSpan` is a safer alternative to preserve the span
   * already present rather than a complete wipe
   */
  val removeCurrentSpan: UIO[Unit] =
    current.set(None)

  /**
   * This is a low level operator meant to be used with [[spanScopedManual]]
   */
  val retrieveCurrentSpan: UIO[Option[ZSpan]] =
    current.get

  def updateCurrentSpan(in: ZSpan): UIO[Unit] =
    current.set(Some(in))

  def restore(in: Option[ZSpan]): UIO[Unit] =
    current.set(in)

  def locally[R, E, A](span: ZSpan)(zio: ZIO[R, E, A]): ZIO[R, E, A] =
    current.locally(Some(span))(zio)

  def withSpan[R, E, A](
    name: String,
    kind: SpanKind = SpanKind.Internal,
    errorHandler: ErrorHandler = ErrorHandler.empty
  )(fn: ZSpan => ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.scoped[R] {
      spanScoped(name, kind, errorHandler).flatMap(span => current.locally(Some(span))(fn(span)))
    }
}
object ZTracer {
  def make(current: FiberRef[Option[ZSpan]], entryPoint: ZEntryPoint): ZTracer =
    new ZTracer(current, entryPoint)

  def span[R, E, A](
    name: String,
    kind: SpanKind = SpanKind.Internal,
    errorHandler: ErrorHandler = ErrorHandler.empty
  )(zio: ZIO[R, E, A]): ZIO[R & ZTracer, E, A] =
    ZIO.service[ZTracer].flatMap(_.span(name, kind, errorHandler)(zio))

  def spanScopedManual(
    name: String,
    kind: SpanKind = SpanKind.Internal,
    errorHandler: ErrorHandler = ErrorHandler.empty
  ): URIO[ZTracer & Scope, ZSpan] =
    ZIO.serviceWithZIO[ZTracer](_.spanScopedManual(name, kind, errorHandler))

  def spanScoped(
    name: String,
    kind: SpanKind = SpanKind.Internal,
    errorHandler: ErrorHandler = ErrorHandler.empty
  ): URIO[ZTracer & Scope, ZSpan] =
    ZIO.serviceWithZIO[ZTracer](_.spanScoped(name, kind, errorHandler))

  def updateCurrentSpan(span: ZSpan): URIO[ZTracer, Unit] =
    ZIO.serviceWithZIO[ZTracer](_.updateCurrentSpan(span))

  def restore(span: Option[ZSpan]): URIO[ZTracer, Unit] =
    ZIO.serviceWithZIO[ZTracer](_.restore(span))

  def locally[R, E, A](span: ZSpan)(zio: ZIO[R, E, A]): ZIO[R & ZTracer, E, A] =
    ZIO.service[ZTracer].flatMap(_.locally(span)(zio))

  val getCurrentSpan: URIO[ZTracer, Option[ZSpan]] =
    ZIO.serviceWithZIO[ZTracer](_.retrieveCurrentSpan)

  val removeCurrentSpan: URIO[ZTracer, Unit] =
    ZIO.serviceWithZIO[ZTracer](_.removeCurrentSpan)

  def withSpan[R, E, A](
    name: String,
    kind: SpanKind = SpanKind.Internal,
    errorHandler: ErrorHandler = ErrorHandler.empty
  )(fn: ZSpan => ZIO[R, E, A]): ZIO[R & ZTracer, E, A] =
    ZIO
      .service[ZTracer]
      .flatMap(_.withSpan(name, kind, errorHandler)(fn))

  def spanSource[R, E, A](
    kind: SpanKind = SpanKind.Internal
  )(zio: ZIO[R, E, A])(implicit fileName: sourcecode.FileName, line: sourcecode.Line): ZIO[R & ZTracer, E, A] =
    ZIO
      .service[ZTracer]
      .flatMap(_.spanSource(kind)(zio)(fileName, line))

  val layer: URLayer[ZEntryPoint, ZTracer] =
    ZLayer.scoped(
      for {
        ep <- ZIO.service[ZEntryPoint]
        // the FiberRef will keep the parent's span when a child fiber joined
        spanRef <- FiberRef.make[Option[ZSpan]](initial = None, join = (parent, _) => parent)
        tracer   = ZTracer.make(spanRef, ep)
      } yield tracer
    )
}
