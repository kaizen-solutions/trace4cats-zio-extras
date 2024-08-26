package io.kaizensolutions.trace4cats.zio.extras

import trace4cats.model.{AttributeValue, SpanContext, SpanKind, TraceHeaders}
import trace4cats.{ErrorHandler, ToHeaders}
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
final class ZTracer private (
  private[extras] val current: FiberRef[ZSpan],
  private[extras] val entryPoint: ZEntryPoint
) { self =>
  def context: UIO[SpanContext] =
    current.get.map(_.context)

  def extractHeaders(headerTypes: ToHeaders = ToHeaders.all): UIO[TraceHeaders] =
    current.get.map(_.extractHeaders(headerTypes))

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
    name: String = "root",
    kind: SpanKind = SpanKind.Internal,
    errorHandler: ErrorHandler = ErrorHandler.empty
  )(fn: ZSpan => ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.scoped[R](
      fromHeadersScoped(headers, name, kind, errorHandler)
        .flatMap(child => current.locally(child)(fn(child)))
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
    current.get.flatMap(_.put(key, value))

  def putAll(fields: (String, AttributeValue)*): UIO[Unit] =
    current.get.flatMap(_.putAll(fields*))

  def spanSource[R, E, A](
    kind: SpanKind = SpanKind.Internal
  )(zio: ZIO[R, E, A])(implicit fileName: sourcecode.FileName, line: sourcecode.Line): ZIO[R, E, A] =
    ZIO.scoped[R] {
      spanScopedManual(s"${fileName.value}:${line.value}", kind)
        .flatMap(span => current.locally(span)(zio))
    }

  def span[R, E, A](
    name: String,
    kind: SpanKind = SpanKind.Internal,
    errorHandler: ErrorHandler = ErrorHandler.empty
  )(zio: ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.scoped[R] {
      spanScopedManual(name, kind, errorHandler).flatMap(span => current.locally(span)(zio))
    }

  /**
   * This is a low level operator and leaves you, the user, to manipulate the
   * current span using [[updateCurrentSpan]]. We recommend using [[spanScoped]]
   * instead.
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
    current.get.flatMap { span =>
      if (span.context == SpanContext.invalid) entryPoint.rootSpan(name, kind, errorHandler)
      else span.child(name, kind, errorHandler)
    }

  /**
   * Works like [[withSpan]] but in the context of a `zio.Scope` and handles
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
          .ensuring(updateCurrentSpan(current))
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
   * @param extractName
   *   is a function that extracts the name of the span
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
    extractName: O => String,
    kind: SpanKind = SpanKind.Internal,
    errorHandler: ErrorHandler = ErrorHandler.empty,
    enrichLogs: Boolean = true
  )(stream: ZStream[R, E, O]): ZStream[R, E, Spanned[O]] =
    stream.mapChunksZIO(
      _.mapZIO(o =>
        fromHeaders[Any, Nothing, Spanned[O]](
          headers = extractHeaders(o),
          name = extractName(o),
          kind = kind,
          errorHandler = errorHandler
        )(span =>
          // extract the child's span header so that all stream transformations are traced under the element
          ZIO.succeed(Spanned(span.extractHeaders(ToHeaders.all), o, enrichLogs))
        )
      )
    )

  /**
   * This operation traces the execution of a (finite) ZStream and the trace
   * will be reported once the stream completes. Do not use this on an infinite
   * stream as it will hold the span open indefinitely.
   *
   * @param name
   *   is the name of the span
   * @param kind
   *   is the kind of the span
   * @param errorHandler
   *   is the error handler for the span
   * @param stream
   *   is the stream that will have its execution traced
   * @tparam R
   *   is the environment type
   * @tparam E
   *   is the error type
   * @tparam O
   *   is the output element type
   * @return
   */
  def traceEntireStream[R, E, O](
    name: String,
    kind: SpanKind = SpanKind.Internal,
    errorHandler: ErrorHandler = ErrorHandler.empty,
    enrich: ZSpan => ZIO[R, E, Any] = (_: ZSpan) => ZIO.unit
  )(stream: ZStream[R, E, O]): ZStream[R, E, O] =
    ZStream.unwrap(
      withSpan(name, kind, errorHandler)(span =>
        enrich(span) *> ZIO.succeed(
          stream.tapError {
            case e: Throwable if span.isSampled => span.put("error.message", e.getLocalizedMessage)
            case error if span.isSampled        => span.put("error.message", error.toString)
            case _                              => ZIO.unit
          }.tapErrorCause {
            case c if c.isDie && span.isSampled => span.put("error.cause", c.prettyPrint)
            case _                              => ZIO.unit
          }
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
  def endTracingEachElement[R, E, O](stream: ZStream[R, E, Spanned[O]]): ZStream[R, E, (O, TraceHeaders)] =
    stream.mapChunks(_.map(s => (s.value, s.headers)))

  /**
   * This is a low level operator meant to be used with [[spanScopedManual]]
   */
  val retrieveCurrentSpan: UIO[ZSpan] =
    current.get

  def updateCurrentSpan(in: ZSpan): UIO[Unit] =
    current.set(in)

  def locally[R, E, A](span: ZSpan)(zio: ZIO[R, E, A]): ZIO[R, E, A] =
    current.locally(span)(zio)

  def withSpan[R, E, A](
    name: String,
    kind: SpanKind = SpanKind.Internal,
    errorHandler: ErrorHandler = ErrorHandler.empty
  )(fn: ZSpan => ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.scoped[R] {
      spanScopedManual(name, kind, errorHandler).flatMap(span => current.locally(span)(fn(span)))
    }
}
object ZTracer {
  def make(current: FiberRef[ZSpan], entryPoint: ZEntryPoint): ZTracer =
    new ZTracer(current, entryPoint)

  def span[R, E, A](
    name: String,
    kind: SpanKind = SpanKind.Internal,
    errorHandler: ErrorHandler = ErrorHandler.empty
  )(zio: ZIO[R, E, A]): ZIO[R & ZTracer, E, A] =
    ZIO.serviceWithZIO[ZTracer](_.span(name, kind, errorHandler)(zio))

  def traceEachElement[R, E, O](
    extractName: O => String,
    kind: SpanKind = SpanKind.Internal,
    errorHandler: ErrorHandler = ErrorHandler.empty,
    enrichLogs: Boolean = true
  )(stream: ZStream[R, E, O])(extractHeaders: O => TraceHeaders): ZStream[R & ZTracer, E, Spanned[O]] =
    ZStream.serviceWithStream[ZTracer](
      _.traceEachElement(extractHeaders, extractName, kind, errorHandler, enrichLogs)(stream)
    )

  def traceEntireStream[R, E, O](
    name: String,
    kind: SpanKind = SpanKind.Internal,
    errorHandler: ErrorHandler = ErrorHandler.empty,
    enrich: ZSpan => ZIO[R, E, Any] = (_: ZSpan) => ZIO.unit
  )(stream: ZStream[R, E, O]): ZStream[R & ZTracer, E, O] =
    ZStream.serviceWithStream[ZTracer](_.traceEntireStream(name, kind, errorHandler, enrich)(stream))

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

  def locally[R, E, A](span: ZSpan)(zio: ZIO[R, E, A]): ZIO[R & ZTracer, E, A] =
    ZIO.serviceWithZIO[ZTracer](_.locally(span)(zio))

  val retrieveCurrentSpan: URIO[ZTracer, ZSpan] =
    ZIO.serviceWithZIO[ZTracer](_.retrieveCurrentSpan)

  def fromHeaders[R, E, A](headers: TraceHeaders, name: String, kind: SpanKind)(
    fn: ZSpan => ZIO[R, E, A]
  ): ZIO[R & ZTracer, E, A] =
    ZIO.serviceWithZIO[ZTracer](_.fromHeaders(headers, name, kind)(fn))

  def withSpan[R, E, A](
    name: String,
    kind: SpanKind = SpanKind.Internal,
    errorHandler: ErrorHandler = ErrorHandler.empty
  )(fn: ZSpan => ZIO[R, E, A]): ZIO[R & ZTracer, E, A] =
    ZIO.serviceWithZIO[ZTracer](_.withSpan(name, kind, errorHandler)(fn))

  def spanSource[R, E, A](
    kind: SpanKind = SpanKind.Internal
  )(zio: ZIO[R, E, A])(implicit fileName: sourcecode.FileName, line: sourcecode.Line): ZIO[R & ZTracer, E, A] =
    ZIO.serviceWithZIO[ZTracer](_.spanSource(kind)(zio)(fileName, line))

  val layer: URLayer[ZEntryPoint, ZTracer] =
    ZLayer.scoped(
      for {
        ep      <- ZIO.service[ZEntryPoint]
        spanRef <- FiberRef.make[ZSpan](initial = ZSpan.noop)
        tracer   = ZTracer.make(spanRef, ep)
      } yield tracer
    )
}
