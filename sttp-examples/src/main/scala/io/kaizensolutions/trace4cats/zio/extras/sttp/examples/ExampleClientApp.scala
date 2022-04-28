package io.kaizensolutions.trace4cats.zio.extras.sttp.examples

import io.kaizensolutions.trace4cats.zio.extras.ZTracer
import io.kaizensolutions.trace4cats.zio.extras.sttp.SttpBackendTracer
import sttp.capabilities
import sttp.capabilities.zio.ZioStreams
import sttp.client3.*
import sttp.client3.httpclient.zio.HttpClientZioBackend
import zio.*
import zio.blocking.Blocking
import zio.clock.Clock
import zio.duration.durationInt

// Spin up the HTTP4S Server Example and then this one
object ExampleClientApp extends App {
  type SttpClient = SttpBackend[Task, ZioStreams & capabilities.WebSockets]

  val tracedBackendManaged: URManaged[Has[ZTracer], SttpClient] =
    (for {
      tracer  <- ZManaged.service[ZTracer]
      backend <- HttpClientZioBackend.managed()
    } yield SttpBackendTracer(tracer, backend)).orDie

  val dependencies: URLayer[Clock & Blocking, Has[ZTracer] & Has[SttpClient]] = {
    val tracerLayer: URLayer[Clock & Blocking, Has[ZTracer]]     = JaegarEntrypoint.live >>> ZTracer.live
    val sttpBackendLayer: URLayer[Has[ZTracer], Has[SttpClient]] = tracedBackendManaged.toLayer

    tracerLayer >+> sttpBackendLayer
  }

  override def run(args: List[String]): URIO[ZEnv, ExitCode] =
    ZIO
      .service[SttpClient]
      .flatMap { client =>
        val sayHello =
          client
            .send(basicRequest.get(uri"http://localhost:8080/hello/1"))
            .tap(r => ZTracer.spanSource()(ZIO.debug(r.statusText) *> ZIO.debug(r.body)))

        val error =
          client
            .send(basicRequest.get(uri"http://localhost:8080/error"))
            .tap(r =>
              ZTracer.span("headers-error")(
                ZTracer.span("printing-body-status-error")(ZIO.debug(r.statusText) *> ZIO.debug(r.body))
              )
            )

        ZTracer
          .span("sttp-client-hello-error-par") {
            sayHello
              .zipPar(error)
              .repeat(Schedule.recurs(10) *> Schedule.spaced(1.second))
          }
      }
      .exitCode
      .provideCustomLayer(dependencies)
}
