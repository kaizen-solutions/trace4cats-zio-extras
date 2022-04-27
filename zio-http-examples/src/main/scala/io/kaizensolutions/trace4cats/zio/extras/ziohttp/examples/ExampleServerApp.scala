package io.kaizensolutions.trace4cats.zio.extras.ziohttp.examples

import io.kaizensolutions.trace4cats.zio.extras.ZTracer
import io.kaizensolutions.trace4cats.zio.extras.ziohttp.server.ZioHttpServerTracer
import zhttp.http.*
import zhttp.service.Server
import zio.*
import zio.clock.Clock
import zio.duration.*
import zio.random.Random

object ExampleServerApp extends App {
  val app: Http[Clock & Random & Has[Db] & Has[ZTracer], Throwable, Request, Response] =
    Http.collectZIO[Request] {

      case Method.GET -> !! / "plaintext" =>
        ZTracer.withSpan("plaintext-fetch-db") { span =>
          for {
            sleep <- random.nextIntBetween(1, 3)
            _     <- span.put("sleep-duration.seconds", sleep)
            _     <- ZTracer.spanSource()(ZIO.sleep(sleep.seconds) *> Db.get(sleep))
          } yield Response
            .text(sleep.toString)
            .updateHeaders(_.addHeader("custom-header", sleep.toString))
            .setStatus(Status.Ok)
        }

      case Method.GET -> !! / "fail" =>
        ZIO.fail(new RuntimeException("Error"))

      case Method.GET -> !! / "bad_gateway" =>
        ZIO.succeed(Response.status(Status.BadGateway))
    }

  override def run(args: List[String]): URIO[ZEnv, ExitCode] =
    ZIO
      .service[ZTracer]
      .flatMap { tracer =>
        val tracedApp = ZioHttpServerTracer.traceApp(tracer, app)
        Server.start(8080, tracedApp)
      }
      .exitCode
      .provideCustomLayer(
        (NewRelicEntrypoint.live >>> ZTracer.live) ++ Db.live
      )
}
