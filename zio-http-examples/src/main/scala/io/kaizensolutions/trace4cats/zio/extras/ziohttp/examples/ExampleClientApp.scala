package io.kaizensolutions.trace4cats.zio.extras.ziohttp.examples

import trace4cats.model.TraceProcess
import io.kaizensolutions.trace4cats.zio.extras.ZTracer
import io.kaizensolutions.trace4cats.zio.extras.ziohttp.client.ZioHttpClientTracer
import zhttp.service.{ChannelFactory, EventLoopGroup}
import zio.*
import zio.Console.printLine

/**
 * Fire up [[ExampleServerApp]] and then run this example client.
 */
object ExampleClientApp extends ZIOAppDefault {
  override val run: ZIO[ZIOAppArgs & Scope, Any, Any] = {
    val reqPlainText =
      ZioHttpClientTracer
        .tracedRequest("http://localhost:8080/plaintext")
        .tap(response => ZTracer.spanSource()(response.bodyAsString.flatMap(printLine(_))))

    val reqFail =
      ZioHttpClientTracer
        .tracedRequest("http://localhost:8080/fail")
        .tap(response => response.bodyAsString.flatMap(printLine(_)))

    val reqBadGateway =
      ZioHttpClientTracer
        .tracedRequest("http://localhost:8080/bad_gateway")
        .tap(response => response.bodyAsString.flatMap(printLine(_)))

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
        ChannelFactory.auto,
        EventLoopGroup.auto(),
        ZLayer.scoped(JaegerEntrypoint.entryPoint(TraceProcess("zio-http-client-example")).orDie),
        ZTracer.layer
      )
  }
}
