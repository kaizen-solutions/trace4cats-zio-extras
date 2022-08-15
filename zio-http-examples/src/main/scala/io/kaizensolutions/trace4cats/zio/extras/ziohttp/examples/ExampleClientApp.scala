package io.kaizensolutions.trace4cats.zio.extras.ziohttp.examples

import trace4cats.model.TraceProcess
import io.kaizensolutions.trace4cats.zio.extras.ZTracer
import io.kaizensolutions.trace4cats.zio.extras.ziohttp.client.ZioHttpClientTracer
import zhttp.service.{ChannelFactory, EventLoopGroup}
import zio.*
import zio.blocking.Blocking
import zio.clock.Clock
import zio.console.putStrLn
import zio.duration.durationInt

/**
 * Fire up [[ExampleServerApp]] and then run this example client.
 */
object ExampleClientApp extends App {
  val dependencies: URLayer[Clock & Blocking, ChannelFactory & EventLoopGroup & Has[ZTracer]] =
    ChannelFactory.auto ++ EventLoopGroup.auto() ++
      (JaegarEntrypoint.entryPoint(TraceProcess("zio-http-client-example")).orDie.toLayer >>> ZTracer.layer)

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = {
    val reqPlainText =
      ZioHttpClientTracer
        .tracedRequest("http://localhost:8080/plaintext")
        .tap(response => ZTracer.spanSource()(response.bodyAsString.flatMap(putStrLn(_))))

    val reqFail =
      ZioHttpClientTracer
        .tracedRequest("http://localhost:8080/fail")
        .tap(response => response.bodyAsString.flatMap(putStrLn(_)))

    val reqBadGateway =
      ZioHttpClientTracer
        .tracedRequest("http://localhost:8080/bad_gateway")
        .tap(response => response.bodyAsString.flatMap(putStrLn(_)))

    ZTracer
      .span("client-request") {
        ZIO
          .collectAllPar_(
            List(
              reqPlainText,
              reqFail,
              reqBadGateway
            )
          )
      }
      .repeat(Schedule.recurs(10) *> Schedule.spaced(1.second))
      .exitCode
      .provideCustomLayer(dependencies)
  }
}
