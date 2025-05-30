package io.kaizensolutions.http4s.examples

import fs2.io.net.Network
import trace4cats.model.TraceProcess
import io.kaizensolutions.trace4cats.zio.extras.ZTracer
import io.kaizensolutions.trace4cats.zio.extras.http4s.client.Http4sClientTracer
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.implicits.*
import org.http4s.{Charset, Response}
import zio.*
import zio.Console.printLine
import zio.interop.catz.*

/**
 * Fire up [[ExampleServerApp]] and then run this example.
 */
object ExampleClientApp extends ZIOAppDefault {
  type ClientEffect[A] = RIO[ZTracer, A]

  implicit val fs2NetworkForClientEffect: Network[ClientEffect] = Network.forAsync[ClientEffect]

  val tracedClient: ZIO[ZTracer & Scope, Throwable, Client[ClientEffect]] =
    for {
      // NOTE: Blocking is necessary to materialize the typeclass instances needed but is not actually used
      // ZTracer is in here because I'm making use of the companion object
      client      <- EmberClientBuilder.default[ZIO[ZTracer, Throwable, *]].build.toScopedZIO
      tracer      <- ZIO.service[ZTracer]
      tracedClient = Http4sClientTracer.traceClient(tracer, client)
    } yield tracedClient

  val run: ZIO[ZIOAppArgs & Scope, Any, Any] = {
    ZIO.scoped {
      tracedClient.flatMap { client =>
        // Eliminating this throws off EntityDecoder derivation in Scala 3 (used by response.as)
        implicit val utf8Charset: Charset = Charset.`UTF-8`

        val sayHello =
          client.get(uri"http://localhost:8080/hello/1") { (response: Response[ClientEffect]) =>
            val printHeaders =
              ZIO.foreachDiscard(response.headers.headers)(header => printLine(s"${header.name}: ${header.value}"))
            val printBody = response.as[String].flatMap(printLine(_))

            ZTracer.spanSource() {
              ZTracer.span("headers-hello")(printHeaders) *> ZTracer.span("printing-body-hello")(printBody)
            }
          }

        val error =
          client.get(uri"http://localhost:8080/error") { response =>
            val printHeaders =
              ZIO.foreachDiscard(response.headers.headers)(header => printLine(s"${header.name}: ${header.value}"))
            val printBody = response.as[String].flatMap(printLine(_))
            ZTracer.spanSource() {
              ZTracer.span("headers-error")(printHeaders) *> ZTracer.span("printing-body-error")(printBody)
            }
          }

        val repeat10Times1SecondPerRepeat: Schedule[Any, Any, Any] = Schedule.recurs(10) *> Schedule.spaced(1.second)
        ZTracer.span("tracer-par-client") {
          sayHello
            .zipPar(error)
            .repeat(repeat10Times1SecondPerRepeat)
            .unit
        }
      }
    }.provide(
      ZLayer.scoped[Any](OltpGrpcEntrypoint.entryPoint(TraceProcess("http4s-client-example"))),
      ZTracer.layer
    )
  }
}
