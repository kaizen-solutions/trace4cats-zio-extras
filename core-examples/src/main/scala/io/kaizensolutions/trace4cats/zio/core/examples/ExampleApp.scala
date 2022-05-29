package io.kaizensolutions.trace4cats.zio.core.examples
import io.janstenpickle.trace4cats.ToHeaders
import io.kaizensolutions.trace4cats.zio.extras.*
import zio.*
import zio.console.putStrLn
import zio.duration.*
import zio.stream.ZStream

object ExampleApp extends App {
  // this approach works if you dont apply any stream parallelism but has less ceremony since you don't carry the
  // Spanned datatype around and you purely rely on the environment and the FiberRef but it breaks in the face of parallelism
  // This method goes in ZTracer
  //  def beginSpan(
  //    name: String,
  //    kind: SpanKind = SpanKind.Internal,
  //    errorHandler: ErrorHandler = ErrorHandler.empty
  //  ): ZStream[Any, Nothing, Unit] =
  //    ZStream
  //      .managed(spanManaged(name, kind, errorHandler))
  //      .tap(updateCurrentSpan)
  //      .as(())
  // Earlier example that shows breakage in the above model
  //    ZStream
  //      .range(1, 10)
  //      .flatMap(elem => ZTracer.beginSpan(s"okaysleep-$elem").as(elem))
  //      .mapM(e => ZTracer.span("plus 1")(putStrLn(s"Adding ${e} + 1 = ${e + 1}") *> ZIO.succeed(e + 1)))
  //      .mapMPar(2)(e =>  // traces are thrown off by parallelism
  //        ZTracer.span("plus 2")(putStrLn(s"Adding ${e} + 2 = ${e + 2}").delay(500.millis) *> ZIO.succeed(e + 2))
  //      )
  //      .tap(_ => ZTracer.removeCurrentSpan)
  //      .runDrain
  //      .exitCode
  //      .provideCustomLayer(JaegarEntrypoint.live >>> ZTracer.live)

  // The approach below is a lot more heavy-handed but works properly in the face of parallelism
  override def run(args: List[String]): URIO[ZEnv, ExitCode] =
    ZStream
      .range(1, 100)
      .mapM(i => ZTracer.withSpan(s"name-$i")(span => ZIO.succeed((i, span.extractHeaders(ToHeaders.standard)))))
      .traceEachElement("in-begin") { case (_, headers) =>
        headers
      }
      .mapThrough(_._1)
      .mapMTraced(e => ZTracer.span(s"plus 1 for $e")(putStrLn(s"Adding ${e} + 1 = ${e + 1}") *> ZIO.succeed(e + 1)))
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
      .provideCustomLayer(JaegarEntrypoint.live >>> ZTracer.live)
}
