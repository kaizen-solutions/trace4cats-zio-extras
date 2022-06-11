package io.kaizensolutions.trace4cats.zio.core.examples
import io.janstenpickle.trace4cats.ToHeaders
import io.kaizensolutions.trace4cats.zio.extras.*
import zio.*
import zio.console.putStrLn
import zio.duration.*
import zio.stream.ZStream

object ExampleApp extends App {

  override def run(args: List[String]): URIO[ZEnv, ExitCode] =
    ZTracer
      .span("streaming-app") {
        ZStream
          .range(1, 100)
          .mapM(i => ZTracer.withSpan(s"name-$i")(span => ZIO.succeed((i, span.extractHeaders(ToHeaders.standard)))))
          .traceEachElement("in-begin") { case (_, headers) =>
            headers
          }
          .mapThrough(_._1)
          .mapMTraced(e =>
            ZTracer.span(s"plus 1 for $e")(putStrLn(s"Adding ${e} + 1 = ${e + 1}") *> ZIO.succeed(e + 1))
          )
          .mapMParTraced(8)(e =>
            ZTracer.span(s"plus 2 for $e")(
              putStrLn(s"Adding ${e} + 2 = ${e + 2}")
                .delay(500.millis) *>
                ZIO.succeed(e + 2)
            )
          )
          .mapMParTraced(3)(e =>
            ZTracer.span(s"plus 4 for $e")(
              ZTracer.spanSource()(
                putStrLn(s"Adding ${e} + 4 = ${e + 4}")
                  .delay(1.second)
              ) *> ZIO.succeed(e + 2)
            )
          )
          .endTracingEachElement
          .runDrain
          .exitCode
      }
      .provideCustomLayer(JaegarEntrypoint.live >>> ZTracer.layer)
}
