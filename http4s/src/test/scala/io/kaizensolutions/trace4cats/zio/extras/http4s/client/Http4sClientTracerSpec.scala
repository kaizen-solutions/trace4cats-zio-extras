package io.kaizensolutions.trace4cats.zio.extras.http4s.client

import cats.effect.Resource
import trace4cats.model.{SpanStatus, TraceProcess}
import io.kaizensolutions.trace4cats.zio.extras.InMemorySpanCompleter
import io.kaizensolutions.trace4cats.zio.extras.http4s.server.Http4sServerTracerSpec.dsl.Ok
import org.http4s.client.Client
import org.http4s.syntax.all.*
import org.http4s.{Header, Headers, Method, Request, Response}
import org.typelevel.ci.CIString
import zio.interop.catz.*
import zio.test.*
import zio.{Scope, Task, URIO, ZIO}

object Http4sClientTracerSpec extends ZIOSpecDefault {
  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("HTTP4S Client Tracer")(
      test("Client requests are traced") {
        for {
          result      <- setup(_ => Resource.pure(Response[Task](status = Ok)))
          (sc, client) = result
          // NOTE: define a scope here otherwise the results won't show up as it uses this Scope which is held open for
          // the entire duration of this test
          response <- ZIO.scoped(client.run(exampleRequest).toScopedZIO)
          spans    <- sc.retrieveCollected
        } yield assertTrue(response.status == Ok, spans.length == 1) && {
          val span = spans.head
          assertTrue(
            span.name == "GET /hello",
            span.attributes("http.status_code").value.value == 200,
            span.attributes("http.url").value.value == "/hello"
          )
        }
      } +
        test("Client errors are traced") {
          for {
            result      <- setup(_ => Resource.eval(ZIO.fail(new Exception("An error has occurred"))))
            (sc, client) = result
            response    <- ZIO.scoped(client.run(exampleRequest).toScopedZIO).exit
            spans       <- sc.retrieveCollected
          } yield assertTrue(response.isFailure: Boolean, spans.length == 1) && {
            val span = spans.head
            assertTrue(
              span.name == "GET /hello",
              span.attributes("http.url").value.value == "/hello",
              span.attributes("error.message").value.value == "An error has occurred",
              span.status == SpanStatus.Internal("An error has occurred")
            )
          }
        } +
        test("Client defects are traced") {
          for {
            result <-
              setup(_ =>
                Resource.eval(
                  ZIO
                    .succeed("Hello")
                    .zipPar(ZIO.die(new IllegalStateException("A defect has occurred")))
                    .zipPar(ZIO.die(new IllegalArgumentException("Another defect has occurred")))
                    .zipRight(ZIO.succeed(Response[Task](status = Ok)))
                )
              )
            (sc, client) = result
            response    <- ZIO.scoped(client.run(exampleRequest).toScopedZIO).exit
            spans       <- sc.retrieveCollected
          } yield assertTrue(response.isFailure, spans.length == 1) && {
            val span = spans.head
            assertTrue(
              span.name == "GET /hello",
              span.attributes("http.url").value.value == "/hello", {
                val cause = span.attributes("error.cause").value.value.toString
                cause.contains("A defect has occurred") &&
                cause.contains("Another defect has occurred")
              }
            )
          }
        } @@ TestAspect.flaky
    )

  def setup(
    response: Request[Task] => Resource[Task, Response[Task]]
  ): URIO[Scope, (InMemorySpanCompleter, Client[Task])] =
    for {
      result      <- InMemorySpanCompleter.entryPoint(TraceProcess("http4s-server-tracer-spec"))
      (sc, ep)     = result
      tracer      <- InMemorySpanCompleter.toZTracer(ep)
      client       = Client[Task](response)
      tracedClient = Http4sClientTracer.traceClient(tracer, client)
    } yield (sc, tracedClient)

  val exampleRequest: Request[Task] =
    Request[Task](
      method = Method.GET,
      uri = uri"/hello",
      headers = Headers(Header.Raw(CIString("hello"), "world"))
    )
}
