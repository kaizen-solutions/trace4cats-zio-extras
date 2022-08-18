package io.kaizensolutions.http4s.examples

import com.comcast.ip4s
import io.kaizensolutions.trace4cats.zio.extras.ZTracer
import io.kaizensolutions.trace4cats.zio.extras.http4s.server.Http4sServerTracer
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.dsl.Http4sDsl
import org.http4s.{HttpApp, HttpRoutes}
import zio.*
import zio.blocking.Blocking
import zio.clock.Clock
import zio.duration.durationInt
import zio.interop.catz.*

import scala.util.Try

object ExampleServerApp extends App {
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
        for {
          tracer <- ZManaged.service[ZTracer]
          httpApp = Http4sServerTracer.traceRoutes(tracer, routes).orNotFound
          port   <- ZManaged.fromOption(ip4s.Port.fromInt(8080)).mapError(_ => new RuntimeException("Invalid Port"))
          server <- EmberServerBuilder
                      .default[Effect]
                      .withHostOption(ip4s.Host.fromString("localhost"))
                      .withPort(port)
                      .withHttpApp(httpApp)
                      .build
                      .toManagedZIO
        } yield server
      }
      .useForever
      .exitCode
      .provideCustomLayer(
        (JaegarEntrypoint.live >>> ZTracer.layer) ++ Db.live
      )
}
