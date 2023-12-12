package io.kaizensolutions.trace4cats.zio.extras.tapir

import io.kaizensolutions.trace4cats.zio.extras.{InMemorySpanCompleter, ZTracer}
import org.http4s.*
import org.http4s.syntax.all.*
import org.typelevel.ci.CIString
import sttp.model.StatusCode
import sttp.tapir.server.http4s.{Http4sServerInterpreter, Http4sServerOptions}
import sttp.tapir.ztapir.*
import trace4cats.{ToHeaders, TraceProcess}
import zio.interop.catz.*
import zio.test.*
import zio.{Scope, Task}

object TraceInterceptorSpec extends ZIOSpecDefault {
  final class TestEndpoint(tracer: ZTracer) {
    private val testEndpoint =
      endpoint.get
        .in("hello")
        .in(path[String]("name"))
        .in("greeting")
        .out(statusCode(StatusCode.Ok))

    val serverLogic: ZServerEndpoint[Any, Any] =
      testEndpoint.zServerLogic(name =>
        tracer.withSpan("moshi") { span =>
          span.put("hello", name).unit
        }
      )
  }

  def spec: Spec[TestEnvironment & Scope, Throwable] = suite("TraceInterceptor specification")(
    test("traces http requests") {
      for {
        result     <- InMemorySpanCompleter.entryPoint(TraceProcess("tapir-trace-interceptor-test"))
        (sc, ep)    = result
        tracer     <- InMemorySpanCompleter.toZTracer(ep)
        interceptor = TraceInterceptor(tracer, headerFormat = ToHeaders.w3c)
        endpoint    = new TestEndpoint(tracer)
        httpApp = Http4sServerInterpreter[Task](
                    Http4sServerOptions
                      .default[Task]
                      .prependInterceptor(interceptor)
                  ).toRoutes(endpoint.serverLogic).orNotFound
        response <- httpApp.run(Request(uri = uri"/hello/cal/greeting"))
        spans    <- sc.retrieveCollected
      } yield assertTrue(
        response.headers.get(CIString("traceparent")).isDefined,
        response.status == Status.Ok,
        spans.exists(_.name == "GET /hello/{name}/greeting"),
        spans.find(_.name == "moshi").exists(_.context.parent.isDefined),
        spans.exists(_.attributes.contains("hello"))
      )
    }
  )
}
