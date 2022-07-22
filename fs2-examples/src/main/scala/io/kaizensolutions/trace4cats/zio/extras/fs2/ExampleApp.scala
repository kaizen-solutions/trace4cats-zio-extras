package io.kaizensolutions.trace4cats.zio.extras.fs2

import io.kaizensolutions.trace4cats.zio.extras.ZTracer
import _root_.fs2.*
import io.janstenpickle.trace4cats.ToHeaders
import zio.*
import zio.blocking.Blocking
import zio.clock.Clock
import zio.duration.*
import zio.interop.catz.*

object ExampleApp extends App {
  type Effect[A] = RIO[Clock & Blocking & Has[ZTracer], A]

  override def run(args: List[String]): URIO[ZEnv, ExitCode] =
    ZTracer
      .span("streaming-app")(
        Stream
          .range(1, 100)
          .covary[Effect]
          .evalMap(i => ZTracer.withSpan(s"name-$i")(span => ZIO.succeed((i, span.extractHeaders(ToHeaders.standard)))))
          .traceEachElement("in-begin") { case (_, headers) => headers }
          .mapThrough(_._1)
          .evalMapTraced("Plus 1")(e =>
            ZTracer.span(s"plus 1 for $e")(ZIO.succeed(println(s"Adding ${e} + 1 = ${e + 1}")) *> ZIO.succeed(e + 1))
          )
          .parEvalMapTraced("Plus 2")(8)(e =>
            ZTracer.span(s"plus 2 for $e")(
              ZIO
                .succeed(println(s"Adding ${e} + 2 = ${e + 2}"))
                .delay(500.millis) *>
                ZIO.succeed(e + 2)
            )
          )
          .parEvalMapTraced("Plus 4")(3)(e =>
            ZTracer.span(s"plus 4 for $e")(
              ZTracer.spanSource()(
                ZIO
                  .succeed(println(s"Adding ${e} + 4 = ${e + 4}"))
                  .delay(1.second)
              ) *> ZIO.succeed(e + 2)
            )
          )
          .endTracingEachElement
          .compile
          .drain
      )
      .exitCode
      .provideCustomLayer(JaegarEntrypoint.live >>> ZTracer.layer)
}
