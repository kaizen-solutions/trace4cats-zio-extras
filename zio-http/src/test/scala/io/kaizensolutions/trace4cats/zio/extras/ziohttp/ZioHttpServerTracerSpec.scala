package io.kaizensolutions.trace4cats.zio.extras.ziohttp

import io.kaizensolutions.trace4cats.zio.extras.ziohttp.server.ZioHttpServerTracer
import io.kaizensolutions.trace4cats.zio.extras.ziohttp.server.ZioHttpServerTracer.SpanNamer
import io.kaizensolutions.trace4cats.zio.extras.{InMemorySpanCompleter, ZTracer}
import trace4cats.TraceProcess
import zio.*
import zio.http.*
import zio.http.model.*
import zio.test.*

object ZioHttpServerTracerSpec extends ZIOSpecDefault {
  val testApp: HttpApp[ZTracer, Nothing] =
    Http.collectZIO[Request] {
      case Method.GET -> !! / "plaintext" =>
        ZTracer.withSpan("plaintext-fetch") { _ =>
          Random
            .nextIntBetween(1, 3)
            .map(sleep =>
              Response
                .text(sleep.toString)
                .updateHeaders(_.addHeader("custom-header", sleep.toString))
                .setStatus(Status.Ok)
            )
        }
      case Method.GET -> !! / "user" / userId =>
        ZIO.succeed(
          Response
            .json(s"{ 'userId': '$userId', 'name': 'Bob' }")
            .setStatus(Status.Ok)
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
          response        <- app.runZIO(Request.get(URL(!! / "plaintext")))
          spans           <- completer.retrieveCollected
          httpSpan        <- ZIO.from(spans.find(_.name == "GET /plaintext"))
          fetchSpan       <- ZIO.from(spans.find(_.name == "plaintext-fetch"))
        } yield {
          assertTrue(response.status == Status.Ok) &&
          assertTrue(spans.length == 2) &&
          assertTrue(httpSpan.attributes.contains("resp.header.custom-header")) &&
          assertTrue(fetchSpan.context.parent.map(_.spanId).contains(httpSpan.context.spanId))
        }
      } +
        test("renamed spans are traced as per the provided function") {
          val customSpanNamer: SpanNamer = { case Method.GET -> !! / "user" / _ =>
            s"GET /user/:userId"
          }

          for {
            result <-
              setup(tracer =>
                (testApp @@ ZioHttpServerTracer.trace(spanNamer = customSpanNamer))
                  .provideEnvironment(ZEnvironment.empty.add(tracer))
              )
            (completer, app) = result
            _               <- app.runZIO(Request.get(URL(!! / "user" / "1234")))
            _               <- app.runZIO(Request.get(URL(!! / "plaintext")))
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
        }
    )

  def setup[A](f: ZTracer => A): RIO[Scope, (InMemorySpanCompleter, A)] =
    for {
      result  <- InMemorySpanCompleter.entryPoint(TraceProcess("zio-http-server-tracer-spec"))
      (sc, ep) = result
      tracer  <- InMemorySpanCompleter.toZTracer(ep)
      a        = f(tracer)
    } yield (sc, a)
}
