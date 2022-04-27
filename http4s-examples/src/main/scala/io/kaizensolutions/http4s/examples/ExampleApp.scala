package io.kaizensolutions.http4s.examples

import io.kaizensolutions.trace4cats.zio.extras.ZTracer
import io.kaizensolutions.trace4cats.zio.extras.http4s.server.Http4sServerTracer
import org.http4s.blaze.server.BlazeServerBuilder
import org.http4s.dsl.Http4sDsl
import org.http4s.{HttpApp, HttpRoutes}
import zio.*
import zio.blocking.Blocking
import zio.clock.Clock
import zio.duration.durationInt
import zio.interop.catz.*

import scala.util.Try

object ExampleApp extends App {
  def routes: HttpRoutes[Effect] = {
    object dsl extends Http4sDsl[Effect]
    import dsl.*

    HttpRoutes.of {

      case GET -> Root / "hello" / id =>
        val myId = Try(id.toInt).getOrElse(1)
        ZTracer
          .spanSource()(Db.get(myId))
          .flatMap(out => ZTracer.span("sleeper")(ZIO.sleep(100.millis)) *> Ok(s"Hello, $out!"))

      case GET -> Root / "error" =>
        InternalServerError("Oh noes!")
    }
  }

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] =
    ZManaged
      .runtime[Clock & Blocking]
      .flatMap { implicit rts =>
        val _ = rts
        ZManaged.service[ZTracer].flatMap { tracer =>
          val httpApp: HttpApp[Effect] = Http4sServerTracer.traceRoutes(tracer, routes).orNotFound

          BlazeServerBuilder[Effect]
            .bindHttp(8080, "localhost")
            .withHttpApp(httpApp)
            .resource
            .toManagedZIO
        }
      }
      .useForever
      .exitCode
      .provideCustomLayer(
        (NewRelicEntrypoint.live >>> ZTracer.live) ++ Db.live
      )
}
