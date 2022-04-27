package io.kaizensolutions.http4s.examples

import io.janstenpickle.trace4cats.model.TraceProcess
import io.kaizensolutions.trace4cats.zio.extras.ZTracer
import io.kaizensolutions.trace4cats.zio.extras.http4s.client.Http4sClientTracer
import org.http4s.blaze.client.BlazeClientBuilder
import org.http4s.implicits.*
import zio.*
import zio.blocking.Blocking
import zio.clock.Clock
import zio.console.{putStrLn, Console}
import zio.duration.*
import zio.interop.catz.*

/**
 * Fire up [[ExampleServerApp]] and then run this example.
 */
object ExampleClientApp extends App {
  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = {
    val tracedClient =
      for {
        // NOTE: Blocking is necessary to materialize the typeclass instances needed but is not actually used
        // ZTracer is in here because I'm making use of the companion object
        client      <- BlazeClientBuilder[ZIO[Console & Clock & Blocking & Has[ZTracer], Throwable, *]].resource.toManagedZIO
        tracer      <- ZManaged.service[ZTracer]
        tracedClient = Http4sClientTracer.traceClient(tracer, client)
      } yield tracedClient

    tracedClient.use { client =>
      val sayHello =
        client.get(uri"http://localhost:8080/hello/1") { response =>
          val printHeaders =
            ZIO.foreach_(response.headers.headers)(header => putStrLn(s"${header.name}: ${header.value}"))
          val printBody = response.as[String].flatMap(putStrLn(_))

          ZTracer.spanSource() {
            ZTracer.span("headers-hello")(printHeaders) *> ZTracer.span("printing-body-hello")(printBody)
          }
        }

      val error =
        client.get(uri"http://localhost:8080/error") { response =>
          val printHeaders =
            ZIO.foreach_(response.headers.headers)(header => putStrLn(s"${header.name}: ${header.value}"))
          val printBody = response.as[String].flatMap(putStrLn(_))
          ZTracer.spanSource() {
            ZTracer.span("headers-error")(printHeaders) *> ZTracer.span("printing-body-error")(printBody)
          }
        }

      ZTracer.span("tracer-par-client") {
        sayHello
          .zipPar(error)
          .repeat(Schedule.recurs(10) *> Schedule.spaced(1.second))
      }
    }.exitCode
      .provideCustomLayer(
        NewRelicEntrypoint.entryPoint(TraceProcess("http4s-client-example")).orDie.toLayer >>> ZTracer.live
      )
  }
}
