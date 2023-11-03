package io.kaizensolutions.trace4cats.zio.extras.ziohttp.examples

import io.kaizensolutions.trace4cats.zio.extras.ZTracer
import io.kaizensolutions.trace4cats.zio.extras.ziohttp.server.ZioHttpServerTracer.trace
import zio.*
import zio.http.*
import zio.logging.backend.SLF4J

object ExampleServerApp extends ZIOAppDefault {
  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] = Runtime.removeDefaultLoggers >>> SLF4J.slf4j

  val http =
    Routes(
      Method.GET / "plaintext" -> handler(
        ZTracer.withSpan("plaintext-fetch-db") { span =>
          for {
            sleep <- Random.nextIntBetween(1, 3)
            _     <- span.put("sleep-duration.seconds", sleep)
            _     <- ZIO.logInfo("HELLO")
            _     <- ZTracer.spanSource()(ZIO.sleep(sleep.seconds) *> Db.get(sleep))
          } yield Response
            .text(sleep.toString)
            .updateHeaders(_.addHeader("custom-header", sleep.toString))
            .status(Status.Ok)
        }
      ),
      Method.GET / "fail"        -> handler(ZIO.fail(new RuntimeException("Error"))),
      Method.GET / "bad_gateway" -> handler(ZIO.succeed(Response.status(Status.BadGateway)))
    )

  val app: HttpApp[Db & ZTracer] =
    http.handleError(error => Response.text(error.getMessage).status(Status.InternalServerError)).toHttpApp

  override val run: ZIO[ZIOAppArgs & Scope, Any, Any] =
    Server
      .serve(app @@ trace(enrichLogs = true))
      .provide(
        Server.default,
        JaegerEntrypoint.live,
        ZTracer.layer,
        Db.live
      )
}
