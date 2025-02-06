package io.kaizensolutions.http4s.examples

import com.comcast.ip4s.{Host, Port}
import fs2.io.net.Network
import io.kaizensolutions.trace4cats.zio.extras.ZTracer
import io.kaizensolutions.trace4cats.zio.extras.http4s.server.Http4sServerTracer
import org.http4s.dsl.Http4sDsl
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.{HttpApp, HttpRoutes}
import zio.*
import zio.interop.catz.*

import scala.util.Try

object ExampleServerApp extends ZIOAppDefault {
  implicit val fs2NetworkForEffect: Network[Effect] = Network.forAsync[Effect]
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

  val run: ZIO[ZIOAppArgs & Scope, Any, Any] =
    ZIO
      .service[ZTracer]
      .flatMap { tracer =>
        val httpApp: HttpApp[Effect] = Http4sServerTracer.traceRoutes(tracer, routes).orNotFound

        val server = {
          ZIO
            .fromEither(Port.fromInt(8080).toRight(new RuntimeException("Invalid Port")))
            .flatMap(port =>
              EmberServerBuilder
                .default[Effect]
                .withHostOption(Host.fromString("localhost"))
                .withPort(port)
                .withHttpApp(httpApp)
                .build
                .toScopedZIO
            )
        }

        server <* ZIO.never
      }
      .provide(
        ZLayer.fromZIO(Scope.make),
        OltpGrpcEntrypoint.live,
        ZTracer.layer,
        Db.live
      )
}
