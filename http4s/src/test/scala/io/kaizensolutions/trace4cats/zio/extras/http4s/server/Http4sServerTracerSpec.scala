package io.kaizensolutions.trace4cats.zio.extras.http4s.server

import io.janstenpickle.trace4cats.model.{AttributeValue, SpanStatus, TraceProcess}
import io.kaizensolutions.trace4cats.zio.extras.{InMemorySpanCompleter, ZTracer}
import org.http4s.dsl.Http4sDsl
import org.http4s.syntax.all.*
import org.http4s.*
import zio.interop.catz.*
import zio.test.*
import zio.{RIO, Scope, Task, ZIO}

object Http4sServerTracerSpec extends ZIOSpecDefault {
  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("HTTP4S Server Tracer specification")(
      test("traces successful server app requests") {
        for {
          result         <- setupApp
          (sc, tracedApp) = result
          req             = Request[Task](method = Method.GET, uri = uri"/hello/42")
          resp           <- tracedApp.run(req)
          spans          <- sc.retrieveCollected
        } yield assertTrue(
          resp.status == Status.Ok,
          spans.length == 1
        ) && {
          val span        = spans.head
          val spanAttribs = cleanupAttributes(span.attributes)
          assertTrue(
            span.name == "GET /hello/42",
            spanAttribs("http.status_message") == "OK",
            spanAttribs("http.status_code") == 200,
            spanAttribs("http.method") == "GET",
            spanAttribs("http.url") == "/hello/42"
          )
        }
      } +
        test("traces failed server app requests") {
          for {
            result         <- setupApp
            (sc, tracedApp) = result
            req             = Request[Task](method = Method.POST, uri = uri"/error")
            resp           <- tracedApp.run(req)
            spans          <- sc.retrieveCollected
          } yield assertTrue(
            resp.status == Status.InternalServerError,
            spans.length == 1
          ) && {
            val span        = spans.head
            val spanAttribs = cleanupAttributes(span.attributes)
            assertTrue(
              span.name == "POST /error",
              spanAttribs("http.status_message") == "Internal Server Error",
              spanAttribs("http.status_code") == 500,
              spanAttribs("http.method") == "POST",
              spanAttribs("http.url") == "/error"
            )
          }
        } +
        test("traces defective server app requests") {
          for {
            result         <- setupApp
            (sc, tracedApp) = result
            req             = Request[Task](method = Method.DELETE, uri = uri"/boom")
            resp           <- tracedApp.run(req).exit
            spans          <- sc.retrieveCollected
          } yield assertTrue(
            !resp.isSuccess,
            spans.length == 1
          ) && {
            val span        = spans.head
            val spanAttribs = cleanupAttributes(span.attributes)
            assertTrue(
              span.name == "DELETE /boom",
              spanAttribs("http.method") == "DELETE",
              spanAttribs("http.url") == "/boom", {
                val cause = spanAttribs("error.cause").toString
                cause.contains("Boom!") && cause.contains("Bang!")
              },
              span.status.toString.contains("Boom!") || span.status == SpanStatus.Cancelled
            )
          }
        } +
        test("traces successful server route requests") {
          for {
            result            <- setupRoutes
            (sc, tracedRoutes) = result
            req                = Request[Task](method = Method.GET, uri = uri"/hello/42")
            optResp           <- tracedRoutes(req).value
            resp              <- ZIO.from(optResp)
            spans             <- sc.retrieveCollected
          } yield assertTrue(
            resp.status == Status.Ok,
            spans.length == 1
          ) && {
            val span        = spans.head
            val spanAttribs = cleanupAttributes(span.attributes)
            assertTrue(
              span.name == "GET /hello/42",
              spanAttribs("http.status_message") == "OK",
              spanAttribs("http.status_code") == 200,
              spanAttribs("http.method") == "GET",
              spanAttribs("http.url") == "/hello/42"
            )
          }
        }
    )

  object dsl extends Http4sDsl[Task]
  import dsl.*

  val routes: HttpRoutes[Task] =
    HttpRoutes
      .of[Task] {
        case GET -> Root / "hello" / id =>
          Ok(s"Hello, $id!")

        case POST -> Root / "error" =>
          InternalServerError("Oh noes!")

        case DELETE -> Root / "boom" =>
          ZIO
            .die(new IllegalArgumentException("Boom!"))
            .zipParRight(ZIO.die(new RuntimeException("Bang!")))
      }

  val setupApp: RIO[Scope, (InMemorySpanCompleter, HttpApp[Task])] =
    setup(t => Http4sServerTracer.traceApp[Any, Throwable](t, routes.orNotFound))

  val setupRoutes: RIO[Scope, (InMemorySpanCompleter, HttpRoutes[Task])] =
    setup(t => Http4sServerTracer.traceRoutes[Any, Throwable](t, routes))

  def setup[A](f: ZTracer => A): RIO[Scope, (InMemorySpanCompleter, A)] =
    for {
      result  <- InMemorySpanCompleter.entryPoint(TraceProcess("http4s-server-tracer-spec"))
      (sc, ep) = result
      tracer  <- InMemorySpanCompleter.toZTracer(ep)
      a        = f(tracer)
    } yield (sc, a)

  def cleanupAttributes(in: Map[String, AttributeValue]): Map[String, Any] =
    in.map { case (k, v) => (k, v.value.value) }
}
