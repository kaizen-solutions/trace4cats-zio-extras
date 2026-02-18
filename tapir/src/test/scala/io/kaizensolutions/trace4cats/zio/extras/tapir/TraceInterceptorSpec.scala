package io.kaizensolutions.trace4cats.zio.extras.tapir

import fs2.Chunk
import io.kaizensolutions.trace4cats.zio.extras.{InMemorySpanCompleter, ZTracer}
import org.http4s.*
import org.http4s.syntax.all.*
import org.typelevel.ci.CIStringSyntax
import sttp.tapir.server.http4s.{Http4sServerInterpreter, Http4sServerOptions}
import sttp.tapir.server.interceptor.log.DefaultServerLog
import trace4cats.{ToHeaders, TraceProcess}
import zio.interop.catz.*
import zio.test.*
import zio.{Cause, Scope, Task, ZIO}

object TraceInterceptorSpec extends ZIOSpecDefault {

  case class TestContext(spanCompleter: InMemorySpanCompleter, httpApp: HttpApp[Task], tracer: ZTracer)

  def makeTest = for {
    result     <- InMemorySpanCompleter.entryPoint(TraceProcess("tapir-trace-interceptor-test"))
    (sc, ep)    = result
    tracer     <- InMemorySpanCompleter.toZTracer(ep)
    interceptor = TraceInterceptor.task(tracer, headerFormat = ToHeaders.w3c)
    endpoint    = TestEndpoint(tracer)
    httpApp = Http4sServerInterpreter[Task](
      Http4sServerOptions
        .customiseInterceptors[Task]
        .prependInterceptor(interceptor)
        .serverLog(
          DefaultServerLog[Task](
            doLogWhenReceived = ZIO.logInfo(_),
            doLogWhenHandled =
              (msg, ex) => ex.fold(ZIO.logInfo(msg))(ex => ZIO.logErrorCause(msg, Cause.fail(ex))),
            doLogAllDecodeFailures =
              (msg, ex) => ex.fold(ZIO.logWarning(msg))(ex => ZIO.logWarningCause(msg, Cause.fail(ex))),
            doLogExceptions = (msg, ex) => ZIO.logErrorCause(msg, Cause.fail(ex)),
            noLog = ZIO.unit
          )
        )
        .options
    ).toRoutes(endpoint.testEndpoint).orNotFound
  } yield TestContext(sc, httpApp, tracer)

  def spec: Spec[TestEnvironment & Scope, Throwable] = suite("TraceInterceptor specification")(
    test("traces http requests - success") {
      for {
        context <- makeTest
        response <- context.httpApp.run(Request(uri = uri"/hello/cal/greeting").withBodyStream(fs2.Stream.chunk(Chunk.from("foo".getBytes)).covary[Task]))
        spans    <- context.spanCompleter.retrieveCollected
        logs     <- ZTestLogger.logOutput
      } yield assertTrue(
        response.headers.get(ci"traceparent").isDefined,
        response.status == Status.Ok,
        spans.exists(_.name == "GET /hello/{name}/greeting"),
        spans.find(_.name == "moshi").exists(_.context.parent.isDefined),
        spans.exists(_.attributes.contains("hello")),
        spans.flatMap(_.attributes.get("resp.status.code")).exists(_.value.value == 200),
        logs.exists(e =>
          e.message().startsWith("Request: GET /hello/cal/greeting") &&
            e.annotations.contains("traceparent")
        )
      )
    },
    test("traces http requests - decode failure") {
      for {
        context <- makeTest
        a <- context.tracer.withSpan("root") { span =>
          val headers = ToHeaders.w3c.fromContext(span.context)
          context.httpApp.run(
            Request(uri = uri"/hello/cal/greeting")
              .withBodyStream(fs2.Stream.chunk(Chunk.from("invalid".getBytes)).covary[Task])
              .withHeaders(headers.values.toSeq.map{ case (k, v) => Header.Raw(k, v)})
          ).map(res => (res, span.context.spanId))
        }
        (response, rootSpanId) = a
        spans    <- context.spanCompleter.retrieveCollected
        logs     <- ZTestLogger.logOutput
      } yield assertTrue(
        response.headers.get(ci"traceparent").isDefined,
        response.status == Status.BadRequest,
        spans.exists(s => s.name == "GET /hello/{name}/greeting" && s.context.parent.exists(_.spanId.value sameElements rootSpanId.value)),
        spans.flatMap(_.attributes.get("resp.status.code")).exists(_.value.value == 400),
        logs.exists(e =>
          e.message().startsWith("Request: GET /hello/cal/greeting") &&
            e.annotations.contains("traceparent")
        )
      )
    },
    test("traces http requests - security failure") {
      for {
        context <- makeTest
        a <- context.tracer.withSpan("root") { span =>
          val headers = ToHeaders.w3c.fromContext(span.context)
          context.httpApp.run(
            Request(uri = uri"/hello/cal/greeting")
              .withHeaders(headers.values.toSeq.map{ case (k, v) => Header.Raw(k, v)})
              .putHeaders(Header.Raw(ci"auth", "invalid"))
          ).map(res => (res, span.context.spanId))
        }
        (response, rootSpanId) = a
        spans    <- context.spanCompleter.retrieveCollected
        logs     <- ZTestLogger.logOutput
      } yield assertTrue(
        response.headers.get(ci"traceparent").isDefined,
        response.status == Status.Unauthorized,
        spans.exists(s => s.name == "GET /hello/{name}/greeting" && s.context.parent.exists(_.spanId.value sameElements rootSpanId.value)),
        spans.flatMap(_.attributes.get("resp.status.code")).exists(_.value.value == 401),
        logs.exists(e =>
          e.message().startsWith("Request: GET /hello/cal/greeting") &&
            e.annotations.contains("traceparent")
        )
      )
    }
  )
}
