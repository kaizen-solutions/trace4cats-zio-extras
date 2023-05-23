package io.kaizensolutions.trace4cats.zio.core.examples
import trace4cats.ToHeaders
import io.kaizensolutions.trace4cats.zio.extras.*
import zio.*
import zio.Console.printLine
import zio.stream.ZStream

object ExampleApp extends ZIOAppDefault {
  val run: ZIO[ZIOAppArgs & Scope, Any, Any] =
    ZTracer
      .span("streaming-app") {
        ZStream
          .range(1, 100)
          .mapZIO(i => ZTracer.withSpan(s"name-$i")(span => ZIO.succeed((i, span.extractHeaders(ToHeaders.standard)))))
          .traceEachElement(element => s"in-begin-$element") { case (_, headers) =>
            headers
          }
          .mapThrough(_._1)
          .mapZIOTraced("Plus 1")(e =>
            ZTracer.span(s"plus 1 for $e")(printLine(s"Adding ${e} + 1 = ${e + 1}") *> ZIO.succeed(e + 1))
          )
          .mapZIOParTraced("Plus 2")(8)(e =>
            ZTracer.span(s"plus 2 for $e")(
              printLine(s"Adding ${e} + 2 = ${e + 2}")
                .delay(500.millis) *>
                ZIO.succeed(e + 2)
            )
          )
          .mapZIOParTraced("Plus 4")(3)(e =>
            ZTracer.span(s"plus 4 for $e")(
              ZTracer.spanSource()(
                printLine(s"Adding ${e} + 4 = ${e + 4}")
                  .delay(1.second)
              ) *> ZIO.succeed(e + 2)
            )
          )
          .endTracingEachElement
          .runDrain
          .exitCode
      }
      .provide(JaegerEntrypoint.live, ZTracer.layer)
}
