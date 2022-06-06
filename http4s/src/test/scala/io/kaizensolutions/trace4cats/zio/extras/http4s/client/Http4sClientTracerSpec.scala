package io.kaizensolutions.trace4cats.zio.extras.http4s.client

import cats.effect.kernel.Resource
import io.janstenpickle.trace4cats.model.TraceProcess
import io.kaizensolutions.trace4cats.zio.extras.InMemorySpanCompleter
import io.kaizensolutions.trace4cats.zio.extras.http4s.server.Http4sServerTracerSpec.dsl.Ok
import org.http4s.client.Client
import org.http4s.syntax.all.*
import org.http4s.{Header, Headers, Method, Request, Response}
import org.typelevel.ci.CIString
import zio.ZIO
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
          result      <- InMemorySpanCompleter.entryPoint(TraceProcess("http4s-server-tracer-spec"))
          (sc, ep)     = result
          tracer      <- InMemorySpanCompleter.toZTracer(ep)
          client       = Client[Effect](_ => Resource.pure(Response[Effect](status = Ok)))
          tracedClient = Http4sClientTracer.traceClient(tracer, client)
          request = Request[Effect](
                      method = Method.GET,
                      uri = uri"/hello",
                      headers = Headers(Header.Raw(CIString("hello"), "world"))
                    )
          response <- tracedClient.run(request).toManagedZIO.useNow
          spans    <- sc.retrieveCollected
        } yield assertTrue(response.status == Ok, spans.length == 1) && {
          val span = spans.head
          assertTrue(
            span.name == "GET /hello",
            span.attributes("http.status_code").value.value == 200,
            span.attributes("http.url").value.value == "/hello"
          )
        }
      }
    )

  type Effect[A] = ZIO[Clock & Blocking, Throwable, A]
}
