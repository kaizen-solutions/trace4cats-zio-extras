package io.kaizensolutions.trace4cats.zio.extras

import trace4cats.kernel.ErrorHandler
import trace4cats.model.SpanKind
import zio.*
import zio.query.ZQuery
import zio.query.Described

package object zquery {
  implicit class ZQueryOps(val tracer: ZTracer) extends AnyVal {
    def withSpanZQuery[R, E, A](
      name: String,
      kind: SpanKind = SpanKind.Internal,
      errorHandler: ErrorHandler = ErrorHandler.empty
    )(fn: ZSpan => ZQuery[R, E, A]): ZQuery[R, E, A] = {
      ZQuery.acquireReleaseWith(Scope.make)(_.close(Exit.unit)) { scope =>
        ZQuery
          .fromZIO(tracer.spanScoped(name, kind, errorHandler))
          .flatMap(fn)
          .provideSomeEnvironment(
            Described(_.add[Scope](scope), name)
          )
      }
    }

    def spanZQuery[R, E, A](
      name: String,
      kind: SpanKind = SpanKind.Internal,
      errorHandler: ErrorHandler = ErrorHandler.empty
    )(zquery: ZQuery[R, E, A]): ZQuery[R, E, A] =
      withSpanZQuery(name, kind, errorHandler)(_ => zquery)
  }

  implicit class ZQueryObjectOps(val tracerObject: ZTracer.type) extends AnyVal {
    def withSpanZQuery[R, E, A](
      name: String,
      kind: SpanKind = SpanKind.Internal,
      errorHandler: ErrorHandler = ErrorHandler.empty
    )(fn: ZSpan => ZQuery[R, E, A]): ZQuery[R & ZTracer, E, A] =
      ZQuery.serviceWithQuery[ZTracer](_.withSpanZQuery(name, kind, errorHandler)(fn))

    def spanZQuery[R, E, A](
      name: String,
      kind: SpanKind = SpanKind.Internal,
      errorHandler: ErrorHandler = ErrorHandler.empty
    )(zquery: ZQuery[R, E, A]): ZQuery[R & ZTracer, E, A] =
      ZQuery.serviceWithQuery[ZTracer](_.spanZQuery(name, kind, errorHandler)(zquery))
  }
}
