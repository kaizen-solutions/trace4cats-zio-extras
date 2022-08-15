package io.kaizensolutions.trace4cats.zio.extras

import trace4cats.{EntryPoint, ErrorHandler, SpanKind, TraceHeaders}
import zio.interop.catz.*
import zio.{Task, UManaged}

/**
 * Entrypoint provides a way to obtain a root span or a span from headers. All
 * spans start here
 * @param underlying
 */
final class ZEntryPoint(private val underlying: EntryPoint[Task]) extends AnyVal { self =>
  def rootSpan(
    name: String,
    kind: SpanKind,
    errorHandler: ErrorHandler = ErrorHandler.empty
  ): UManaged[ZSpan] =
    underlying
      .root(name, kind, errorHandler)
      .toManagedZIO
      .orDie
      .map(ZSpan.make)

  def fromHeadersOtherwiseRoot(
    headers: TraceHeaders,
    kind: SpanKind = SpanKind.Internal,
    name: String = "root",
    errorHandler: ErrorHandler = ErrorHandler.empty
  ): UManaged[ZSpan] =
    underlying
      .continueOrElseRoot(name, kind, headers, errorHandler)
      .toManagedZIO
      .orDie
      .map(ZSpan.make)
}
