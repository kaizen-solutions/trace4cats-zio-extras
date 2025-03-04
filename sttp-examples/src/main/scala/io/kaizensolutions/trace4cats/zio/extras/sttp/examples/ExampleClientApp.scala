package io.kaizensolutions.trace4cats.zio.extras.sttp.examples

import io.kaizensolutions.trace4cats.zio.extras.{ZEntryPoint, ZTracer}
import io.kaizensolutions.trace4cats.zio.extras.sttp.SttpBackendTracer
import sttp.client3.*
import sttp.client3.httpclient.zio.HttpClientZioBackend
import zio.*

// Spin up the HTTP4S Server Example and then this one
object ExampleClientApp extends ZIOAppDefault {
  type SttpClient = SttpBackend[Task, Any]

  val tracedBackend: URIO[Scope & ZTracer, SttpClient] =
    (for {
      tracer  <- ZIO.service[ZTracer]
      backend <- HttpClientZioBackend.scoped()
    } yield SttpBackendTracer(tracer, backend)).orDie

  val tracerLayer: URLayer[ZEntryPoint, ZTracer] =
    OltpGrpcEntrypoint.live >>> ZTracer.layer

  val run: ZIO[ZIOAppArgs & Scope, Any, Any] =
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

        val schedule: Schedule[Any, Any, Any] = Schedule.recurs(10) *> Schedule.spaced(1.second)

        ZTracer
          .span("sttp-client-hello-error-par") {
            sayHello
              .zipPar(error)
              .repeat(schedule)
          }
      }
      .provide(
        OltpGrpcEntrypoint.live,
        tracerLayer,
        ZLayer.scoped[ZTracer](tracedBackend)
      )
}
