package io.kaizensolutions.trace4cats.zio.extras

import trace4cats.ErrorHandler
import trace4cats.EntryPoint
import trace4cats.model.{SpanKind, TraceHeaders}
import zio.interop.catz.*
import zio.{Scope, Task, URIO}

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
  ): URIO[Scope, ZSpan] =
    underlying
      .root(name, kind, errorHandler)
      .toScopedZIO
      .orDie
      .map(ZSpan.make)

  def fromHeadersOtherwiseRoot(
    headers: TraceHeaders,
    kind: SpanKind = SpanKind.Internal,
    name: String = "root",
    errorHandler: ErrorHandler = ErrorHandler.empty
  ): URIO[Scope, ZSpan] =
    underlying
      .continueOrElseRoot(name, kind, headers, errorHandler)
      .toScopedZIO
      .orDie
      .map(ZSpan.make)
}
