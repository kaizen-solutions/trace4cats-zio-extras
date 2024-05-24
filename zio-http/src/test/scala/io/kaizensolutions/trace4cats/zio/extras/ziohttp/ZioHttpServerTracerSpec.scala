package io.kaizensolutions.trace4cats.zio.extras.ziohttp

import io.kaizensolutions.trace4cats.zio.extras.ziohttp.server.ZioHttpServerTracer
import io.kaizensolutions.trace4cats.zio.extras.{InMemorySpanCompleter, ZTracer}
import trace4cats.model.CompletedSpan
import trace4cats.{ToHeaders, TraceProcess}
import zio.*
import zio.http.*
import zio.test.*

object ZioHttpServerTracerSpec extends ZIOSpecDefault {
  val customHeaderName = "custom-header"
  val testApp =
    Routes(
      Method.GET / "plaintext" -> handler(
        ZTracer.withSpan("plaintext-fetch") { _ =>
          Random
            .nextIntBetween(1, 3)
            .map(sleep =>
              Response
                .text(sleep.toString)
                .updateHeaders(_.addHeader(customHeaderName, sleep.toString))
                .status(Status.Ok)
            )
        }
      ),
      Method.GET / "user" / string("userId") -> handler((userId: String, _: Request) =>
        Response
          .json(s"{ 'userId': '$userId', 'name': 'Bob' }")
          .status(Status.Ok)
      )
    )

  val spec: Spec[TestEnvironment & Scope, Any] =
    suite("ZIO HTTP Server Tracer Specification")(
      suite("Tracing middleware")(
        test("traces http requests") {
          for {
            result <-
              setup(tracer =>
                (testApp @@ ZioHttpServerTracer.trace())
                  .provideEnvironment(ZEnvironment.empty.add(tracer))
              )
            (completer, app)     = result
            response            <- app.runZIO(Request.get(URL(Path("plaintext"))))
            spans               <- completer.retrieveCollected
            httpSpan            <- ZIO.from(spans.find(_.name == "GET /plaintext"))
            fetchSpan           <- ZIO.from(spans.find(_.name == "plaintext-fetch"))
            parentSpanIdOfFetch <- ZIO.fromOption(fetchSpan.context.parent.map(_.spanId))
            spanIdOfHttp         = httpSpan.context.spanId
            // This is done because assertTrue gets confused response.status and response.status(...)
            responseStatus = response.status
          } yield assertTrue(
            responseStatus == Status.Ok,
            spans.length == 2,
            httpSpan.attributes.contains(s"resp.header.$customHeaderName"),
            parentSpanIdOfFetch == spanIdOfHttp
          )
        } +
          test("spans with path parameters have reduced cardinality automatically") {
            for {
              result <-
                setup(tracer =>
                  (testApp @@ ZioHttpServerTracer.trace()).provideEnvironment(ZEnvironment.empty.add(tracer))
                )
              (completer, app) = result
              _               <- app.runZIO(Request.get(URL(Path("user") / "1234")))
              _               <- app.runZIO(Request.get(URL(Path("plaintext"))))
              spans           <- completer.retrieveCollected
              _ <- ZIO
                     .from(spans.find(_.name == "GET /user/{userId}"))
                     .orElseFail(new IllegalStateException("Expected userId span not found"))
              _ <- ZIO
                     .from(spans.find(_.name == "GET /plaintext"))
                     .orElseFail(new IllegalStateException("Expected plaintext span not found"))
              _ <- ZIO
                     .from(spans.find(_.name == "plaintext-fetch"))
                     .orElseFail(new IllegalStateException("Expected plaintext-fetch span not found"))
            } yield assertTrue(spans.length == 3)
          }
      ) + suite("Header injection middleware") {
        val app = Routes(
          Method.GET / ""       -> handler(ZIO.succeed(Response.ok)),
          (Method.GET / "fail") -> handler(ZIO.fail(new RuntimeException("fail")))
        ).handleError(_ => Response.internalServerError)

        val wrappedApp =
          app @@ ZioHttpServerTracer.injectHeaders() @@ ZioHttpServerTracer.trace()

        test("Succeeds on a successful response") {
          for {
            res                <- wrappedApp.runZIO(Request.get(URL.empty))
            spans              <- InMemorySpanCompleter.retrieveCollected
            httpHeadersFromSpan = toHttpHeaders(spans.head, ToHeaders.standard)
            // This is done because assertTrue gets confused res.headers and res.headers(...)
            responseHeaders = res.headers
          } yield assertTrue(responseHeaders.toSeq.diff(httpHeadersFromSpan.toSeq).isEmpty)
        } + test("Succeeds on a failing response")(
          for {
            resActual <- wrappedApp.runZIO(Request.get(URL(Path("fail"))))
            spans     <- InMemorySpanCompleter.retrieveCollected
            expected   = toHttpHeaders(spans.head, ToHeaders.standard)
          } yield assertTrue(expected.toSet.subsetOf(resActual.headers.toSet))
        )
      }.provide(InMemorySpanCompleter.layer("foo-app"))
    )

  def setup[A](f: ZTracer => A): RIO[Scope, (InMemorySpanCompleter, A)] =
    for {
      result  <- InMemorySpanCompleter.entryPoint(TraceProcess("zio-http-server-tracer-spec"))
      (sc, ep) = result
      tracer  <- InMemorySpanCompleter.toZTracer(ep)
      a        = f(tracer)
    } yield (sc, a)

  private def toHttpHeaders(span: CompletedSpan, whichHeaders: ToHeaders): Headers =
    Headers(
      whichHeaders.fromContext(span.context).values.collect {
        case (k, v) if v.nonEmpty => Header.Custom(k.toString, v)
      }
    )
}
