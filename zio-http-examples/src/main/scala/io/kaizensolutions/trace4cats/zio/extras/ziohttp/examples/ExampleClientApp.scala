package io.kaizensolutions.trace4cats.zio.extras.ziohttp.examples

import trace4cats.model.TraceProcess
import io.kaizensolutions.trace4cats.zio.extras.ZTracer
import io.kaizensolutions.trace4cats.zio.extras.ziohttp.client.ZioHttpClientTracer
import zio.*
import zio.Console.printLine
import zio.http.ZClient

/**
 * Fire up [[ExampleServerApp]] and then run this example client.
 */
object ExampleClientApp extends ZIOAppDefault {
  override val run: ZIO[ZIOAppArgs & Scope, Any, Any] = {
    val reqPlainText =
      ZioHttpClientTracer
        .tracedRequest("http://localhost:8080/plaintext")
        .tap(response => ZTracer.spanSource()(response.body.asString.flatMap(printLine(_))))

    val reqFail =
      ZioHttpClientTracer
        .tracedRequest("http://localhost:8080/fail")
        .tap(response => response.body.asString.flatMap(printLine(_)))

    val reqBadGateway =
      ZioHttpClientTracer
        .tracedRequest("http://localhost:8080/bad_gateway")
        .tap(response => response.body.asString.flatMap(printLine(_)))

    val schedule: Schedule[Any, Any, Any] = Schedule.recurs(10) *> Schedule.spaced(1.second)

    ZTracer
      .span("client-request") {
        ZIO
          .collectAllParDiscard(
            List(
              reqPlainText,
              reqFail,
              reqBadGateway
            )
          )
      }
      .repeat(schedule)
      .exitCode
      .provide(
        ZClient.default,
        ZLayer.scoped(JaegerEntrypoint.entryPoint(TraceProcess("zio-http-client-example")).orDie),
        ZTracer.layer
      )
  }
}
