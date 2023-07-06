package io.kaizensolutions.trace4cats.zio.extras.ziohttp

import io.kaizensolutions.trace4cats.zio.extras.ziohttp.server.ZioHttpServerTracer
import io.kaizensolutions.trace4cats.zio.extras.ziohttp.server.ZioHttpServerTracer.SpanNamer
import io.kaizensolutions.trace4cats.zio.extras.{InMemorySpanCompleter, ZTracer}
import trace4cats.model.CompletedSpan
import trace4cats.{ToHeaders, TraceProcess}
import zio.*
import zio.http.*
import zio.test.*

object ZioHttpServerTracerSpec extends ZIOSpecDefault {
  val testApp: HttpApp[ZTracer, Nothing] =
    Http.collectZIO[Request] {
      case Method.GET -> Root / "plaintext" =>
        ZTracer.withSpan("plaintext-fetch") { _ =>
          Random
            .nextIntBetween(1, 3)
            .map(sleep =>
              Response
                .text(sleep.toString)
                .updateHeaders(_.addHeader("custom-header", sleep.toString))
                .withStatus(Status.Ok)
            )
        }
      case Method.GET -> Root / "user" / userId =>
        ZIO.succeed(
          Response
            .json(s"{ 'userId': '$userId', 'name': 'Bob' }")
            .withStatus(Status.Ok)
        )
    }

  val spec: Spec[TestEnvironment & Scope, Any] =
    suite("ZIO HTTP Server Tracer Specification")(
      test("traces http requests") {
        for {
          result <-
            setup(tracer =>
              (testApp @@ ZioHttpServerTracer.trace())
                .provideEnvironment(ZEnvironment.empty.add(tracer))
            )
          (completer, app) = result
          response        <- app.runZIO(Request.get(URL(Root / "plaintext")))
          spans           <- completer.retrieveCollected
          httpSpan        <- ZIO.from(spans.find(_.name == "GET /plaintext"))
          fetchSpan       <- ZIO.from(spans.find(_.name == "plaintext-fetch"))
        } yield assertTrue(
          response.status == Status.Ok,
          spans.length == 2,
          httpSpan.attributes.contains("resp.header.custom-header"),
          fetchSpan.context.parent.map(_.spanId).contains(httpSpan.context.spanId)
        )
      } +
        test("renamed spans are traced as per the provided function") {
          val customSpanNamer: SpanNamer = { case Method.GET -> Root / "user" / _ =>
            s"/user/:userId"
          }

          for {
            result <-
              setup(tracer =>
                (testApp @@ ZioHttpServerTracer.trace(spanNamer = customSpanNamer))
                  .provideEnvironment(ZEnvironment.empty.add(tracer))
              )
            (completer, app) = result
            _               <- app.runZIO(Request.get(URL(Root / "user" / "1234")))
            _               <- app.runZIO(Request.get(URL(Root / "plaintext")))
            spans           <- completer.retrieveCollected
            _ <- ZIO
                   .from(spans.find(_.name == "GET /user/:userId"))
                   .orElseFail(new IllegalStateException("Expected userId span not found"))
            _ <- ZIO
                   .from(spans.find(_.name == "GET /plaintext"))
                   .orElseFail(new IllegalStateException("Expected plaintext span not found"))
            _ <- ZIO
                   .from(spans.find(_.name == "plaintext-fetch"))
                   .orElseFail(new IllegalStateException("Expected plaintext-fetch span not found"))
          } yield assertTrue(spans.length == 3)
        } + suite("Header injection") {
          val app = Http.collectZIO[Request] {
            case Method.GET -> Root          => ZIO.succeed(Response.ok)
            case Method.GET -> Root / "fail" => ZIO.fail(new RuntimeException("fail"))
          }
          val wrappedApp: http.App[ZTracer] =
            app.withDefaultErrorResponse @@ ZioHttpServerTracer.injectHeaders() @@ ZioHttpServerTracer.trace()

          test("Succeeds on a successful response") {
            for {
              res                <- wrappedApp.runZIO(Request.get(URL(Root)))
              spans              <- InMemorySpanCompleter.retrieveCollected
              httpHeadersFromSpan = toHttpHeaders(spans.head, ToHeaders.standard)
            } yield assertTrue(
              res.headers.toSeq
                .diff(httpHeadersFromSpan.toSeq)
                .isEmpty
            )
          } + test("Succeeds on a failing response") {
            for {
              res                <- wrappedApp.runZIO(Request.get(URL(Root / "fail")))
              spans              <- InMemorySpanCompleter.retrieveCollected
              httpHeadersFromSpan = toHttpHeaders(spans.head, ToHeaders.standard)
            } yield assertTrue(
              res.headers.toSeq
                .diff(httpHeadersFromSpan.toSeq)
                .isEmpty
            )
          }

        }.provide(
          InMemorySpanCompleter.layer("foo-app")
        )
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
