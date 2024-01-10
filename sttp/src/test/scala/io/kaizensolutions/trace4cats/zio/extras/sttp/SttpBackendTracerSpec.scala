package io.kaizensolutions.trace4cats.zio.extras.sttp

import io.kaizensolutions.trace4cats.zio.extras.{InMemorySpanCompleter, ZTracer}
import sttp.client3.impl.zio.RIOMonadAsyncError
import sttp.client3.testing.SttpBackendStub
import trace4cats.TraceProcess
import zio.test.*
import sttp.client3.*
import sttp.client3.logging.{Logger, LoggingBackend}
import zio.*

object SttpBackendTracerSpec extends ZIOSpecDefault {

  private val successBackend = SttpBackendStub(new RIOMonadAsyncError[Any])
    .whenAnyRequest
    .thenRespondOk()

  private val ztracerEnv: ZIO[Scope, Nothing, ZEnvironment[InMemorySpanCompleter & ZTracer]] = for {
    result <- InMemorySpanCompleter.entryPoint(TraceProcess("tapir-trace-interceptor-test"))
    (sc, ep) = result
    tracer <- InMemorySpanCompleter.toZTracer(ep)
  } yield ZEnvironment(sc).add(tracer)

  val zioLogger = new Logger[Task] {
    def apply(level: logging.LogLevel, message: => String): Task[Unit] =
      ZIO.log(message)

    def apply(level: logging.LogLevel, message: => String, t: Throwable): Task[Unit] =
      ZIO.log(message)
  }

  def spec: Spec[TestEnvironment & Scope, Any] = suite("Sttp backend tracer")(
    test("Traces requests"){
      for {
        tracer <- ZIO.service[ZTracer]
        completer <- ZIO.service[InMemorySpanCompleter]
        backend = SttpBackendTracer(tracer,
          LoggingBackend(successBackend, zioLogger)
        )
        _ <- basicRequest
          .get(uri"http://host/foo/bar")
          .send(backend)
        logs <- ZTestLogger.logOutput
        spans <- completer.retrieveCollected.debug("spans")
      } yield assertTrue(
        logs.filter(_.message().contains("GET /foo/bar")).forall(_.annotations.contains("X-B3-TraceId")),
        spans.size == 1,
        spans.exists( s =>
          s.name == "GET /foo/bar" &&
            s.attributes.get("remote.service.hostname").is(_.some).value.value == "host" &&
            s.status.isOk &&
            s.attributes.get("resp.status.code").is(_.some).value.value == 200
        ),
      )
    }
  ).provide(
    ZLayer.scopedEnvironment(
      ztracerEnv
    ),
  )
}
