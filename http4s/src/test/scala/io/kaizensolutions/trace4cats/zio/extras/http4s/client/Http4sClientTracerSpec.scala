package io.kaizensolutions.trace4cats.zio.extras.http4s.client

import cats.effect.Resource
import io.janstenpickle.trace4cats.model.TraceProcess
import io.kaizensolutions.trace4cats.zio.extras.InMemorySpanCompleter
import io.kaizensolutions.trace4cats.zio.extras.http4s.server.Http4sServerTracerSpec.dsl.Ok
import org.http4s.client.Client
import org.http4s.syntax.all.*
import org.http4s.{Header, Headers, Method, Request, Response}
import org.typelevel.ci.CIString
import zio.interop.catz.*
import zio.test.*
import zio.{Scope, Task, ZIO}

object Http4sClientTracerSpec extends ZIOSpecDefault {
  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("HTTP4S Client Tracer")(
      test("Client requests are traced") {
        for {
          result      <- InMemorySpanCompleter.entryPoint(TraceProcess("http4s-server-tracer-spec"))
          (sc, ep)     = result
          tracer      <- InMemorySpanCompleter.toZTracer(ep)
          client       = Client[Task](_ => Resource.pure(Response[Task](status = Ok)))
          tracedClient = Http4sClientTracer.traceClient(tracer, client)
          request = Request[Task](
                      method = Method.GET,
                      uri = uri"/hello",
                      headers = Headers(Header.Raw(CIString("hello"), "world"))
                    )
          // NOTE: define a scope here otherwise the results won't show up as it uses this Scope which is held open for
          // the entire duration of this test
          response <- ZIO.scoped(tracedClient.run(request).toScopedZIO)
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
}
