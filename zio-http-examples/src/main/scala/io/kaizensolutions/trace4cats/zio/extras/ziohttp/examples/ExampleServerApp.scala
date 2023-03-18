package io.kaizensolutions.trace4cats.zio.extras.ziohttp.examples

import io.kaizensolutions.trace4cats.zio.extras.ZTracer
import io.kaizensolutions.trace4cats.zio.extras.ziohttp.server.ZioHttpServerTracer.trace
import zio.http.*
import zio.*
import zio.http.model.{Method, Status}

object ExampleServerApp extends ZIOAppDefault {
  val http: Http[Db & ZTracer, Throwable, Request, Response] =
    Http.collectZIO[Request] {
      case Method.GET -> !! / "plaintext" =>
        ZTracer.withSpan("plaintext-fetch-db") { span =>
          for {
            sleep <- Random.nextIntBetween(1, 3)
            _     <- span.put("sleep-duration.seconds", sleep)
            _     <- ZIO.logInfo("HELLO")
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

  val app: App[Db & ZTracer] = http.mapError(_ => Response.status(Status.InternalServerError))

  override val run: ZIO[ZIOAppArgs & Scope, Any, Any] =
    Server
      .serve(app @@ trace())
      .provide(
        Server.default,
        JaegerEntrypoint.live,
        ZTracer.layer,
        Db.live
      )
}
