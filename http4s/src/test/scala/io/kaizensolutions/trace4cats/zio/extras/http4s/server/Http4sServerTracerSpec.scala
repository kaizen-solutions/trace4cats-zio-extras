package io.kaizensolutions.trace4cats.zio.extras.http4s.server

import io.janstenpickle.trace4cats.model.{AttributeValue, TraceProcess}
import io.kaizensolutions.trace4cats.zio.extras.InMemorySpanCompleter
import org.http4s.dsl.Http4sDsl
import org.http4s.syntax.all.*
import org.http4s.{HttpApp, HttpRoutes, Method, Request, Status}
import zio.{URIO, ZIO}
import zio.blocking.Blocking
import zio.clock.Clock
import zio.interop.catz.*
import zio.test.*
import zio.test.environment.TestEnvironment

object Http4sServerTracerSpec extends DefaultRunnableSpec {
  override def spec: ZSpec[TestEnvironment, Any] =
    suite("HTTP4S Server Tracer specification")(
      testM("traces successful server requests") {
        for {
          result         <- setup
          (sc, tracedApp) = result
          req             = Request[Effect](method = Method.GET, uri = uri"/hello/42")
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
        testM("traces failed server requests") {
          for {
            result         <- setup
            (sc, tracedApp) = result
            req             = Request[Effect](method = Method.POST, uri = uri"/error")
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
        testM("traces defective server requests") {
          for {
            result         <- setup
            (sc, tracedApp) = result
            req             = Request[Effect](method = Method.DELETE, uri = uri"/boom")
            resp           <- tracedApp.run(req).run
            spans          <- sc.retrieveCollected
          } yield assertTrue(
            !resp.succeeded,
            spans.length == 1
          ) && {
            val span        = spans.head
            val spanAttribs = cleanupAttributes(span.attributes)
            assertTrue(
              span.name == "DELETE /boom",
              spanAttribs("http.method") == "DELETE",
              spanAttribs("http.url") == "/boom",
              span.status.toString.contains("Boom!")
            )
          }
        }
    )

  type Effect[A] = ZIO[Clock & Blocking, Throwable, A]
  object dsl extends Http4sDsl[Effect]
  import dsl.*

  val app: HttpApp[Effect] =
    HttpRoutes
      .of[Effect] {
        case GET -> Root / "hello" / id =>
          Ok(s"Hello, $id!")

        case POST -> Root / "error" =>
          InternalServerError("Oh noes!")

        case DELETE -> Root / "boom" =>
          ZIO.dieMessage("Boom!")
      }
      .orNotFound

  val setup: URIO[Clock & Blocking, (InMemorySpanCompleter, HttpApp[Effect])] =
    for {
      result   <- InMemorySpanCompleter.entryPoint(TraceProcess("http4s-server-tracer-spec"))
      (sc, ep)  = result
      tracer   <- InMemorySpanCompleter.toZTracer(ep)
      tracedApp = Http4sServerTracer.traceApp[Clock & Blocking, Throwable](tracer, app)
    } yield (sc, tracedApp)

  def cleanupAttributes(in: Map[String, AttributeValue]): Map[String, Any] =
    in.map { case (k, v) => (k, v.value.value) }
}
