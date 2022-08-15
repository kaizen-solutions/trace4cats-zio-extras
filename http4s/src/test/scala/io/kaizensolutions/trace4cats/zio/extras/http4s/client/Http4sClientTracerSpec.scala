package io.kaizensolutions.trace4cats.zio.extras.http4s.client

import cats.effect.kernel.Resource
import trace4cats.model.TraceProcess
import io.kaizensolutions.trace4cats.zio.extras.InMemorySpanCompleter
import io.kaizensolutions.trace4cats.zio.extras.http4s.server.Http4sServerTracerSpec.dsl.Ok
import org.http4s.client.Client
import org.http4s.syntax.all.*
import org.http4s.{Header, Headers, Method, Request, Response}
import org.typelevel.ci.CIString
import zio.{Exit, ZIO}
import zio.blocking.Blocking
import zio.clock.Clock
import zio.interop.catz.*
import zio.test.*
import zio.test.environment.TestEnvironment

object Http4sClientTracerSpec extends DefaultRunnableSpec {
  override def spec: ZSpec[TestEnvironment, Any] =
    suite("HTTP4S Client Tracer")(
      testM("Client requests are traced") {
        for {
          result      <- setup(_ => Resource.pure(Response[Effect](status = Ok)))
          (client, sc) = result
          request = Request[Effect](
                      method = Method.GET,
                      uri = uri"/hello",
                      headers = Headers(Header.Raw(CIString("hello"), "world"))
                    )
          response <- client.run(request).toManagedZIO.useNow
          spans    <- sc.retrieveCollected
        } yield assertTrue(response.status == Ok, spans.length == 1) && {
          val span = spans.head
          assertTrue(
            span.name == "GET /hello",
            span.attributes("http.status_code").value.value == 200,
            span.attributes("http.url").value.value == "/hello"
          )
        }
      } + testM("Client errors are traced") {
        for {
          result      <- setup(_ => Resource.eval(ZIO.fail(new IllegalAccessError("oops"))))
          (client, sc) = result
          result: Exit[Throwable, Response[Effect]] <-
            client.run(Request[Effect](method = Method.GET, uri = uri"/hello")).toManagedZIO.useNow.run
          spans <- sc.retrieveCollected
        } yield assertTrue(!result.succeeded, spans.length == 1) && {
          val span = spans.head
          assertTrue(
            span.name == "GET /hello",
            span.attributes("http.method").value.value == "GET",
            span.attributes("http.url").value.value == "/hello",
            span.attributes("error.message").value.value.toString.contains("oops")
          )
        }
      } + testM("Client defects are traced") {
        for {
          result      <- setup(_ => Resource.eval(ZIO.die(new IllegalAccessError("oops"))))
          (client, sc) = result
          result: Exit[Throwable, Response[Effect]] <-
            client.run(Request[Effect](method = Method.GET, uri = uri"/hello")).toManagedZIO.useNow.run
          spans <- sc.retrieveCollected
        } yield assertTrue(!result.succeeded, spans.length == 1) && {
          val span = spans.head
          assertTrue(
            span.name == "GET /hello",
            span.attributes("http.method").value.value == "GET",
            span.attributes("http.url").value.value == "/hello",
            span.attributes("error.cause").value.value.toString.contains("oops")
          )
        }
      }
    )

  type Effect[A] = ZIO[Clock & Blocking, Throwable, A]

  def setup(
    f: Request[Effect] => Resource[Effect, Response[Effect]]
  ): Effect[(Client[Effect], InMemorySpanCompleter)] =
    for {
      result      <- InMemorySpanCompleter.entryPoint(TraceProcess("http4s-server-tracer-spec"))
      (sc, ep)     = result
      tracer      <- InMemorySpanCompleter.toZTracer(ep)
      client       = Client[Effect](f)
      tracedClient = Http4sClientTracer.traceClient(tracer, client)
    } yield (tracedClient, sc)
}
