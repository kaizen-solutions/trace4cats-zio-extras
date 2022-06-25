package io.kaizensolutions.trace4cats.zio.extras.ziohttp.examples

import io.kaizensolutions.trace4cats.zio.extras.ZTracer
import io.kaizensolutions.trace4cats.zio.extras.ziohttp.server.ZioHttpServerTracer.trace
import zhttp.http.*
import zhttp.service.Server
import zio.*

object ExampleServerApp extends ZIOAppDefault {
  val app: Http[Db & ZTracer, Throwable, Request, Response] =
    Http.collectZIO[Request] {

      case Method.GET -> !! / "plaintext" =>
        ZTracer.withSpan("plaintext-fetch-db") { span =>
          for {
            sleep <- Random.nextIntBetween(1, 3)
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

  override val run: ZIO[ZIOAppArgs & Scope, Any, Any] =
    Server
      .start(8080, app @@ trace)
      .provide(
        JaegerEntrypoint.live,
        ZTracer.layer,
        Db.live
      )
}
