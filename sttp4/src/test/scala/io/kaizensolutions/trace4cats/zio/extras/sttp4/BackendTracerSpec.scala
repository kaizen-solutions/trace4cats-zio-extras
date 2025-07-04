package io.kaizensolutions.trace4cats.zio.extras.sttp4

import io.kaizensolutions.trace4cats.zio.extras.{InMemorySpanCompleter, ZTracer}
import sttp.client4.impl.zio.RIOMonadAsyncError
import sttp.client4.testing.BackendStub
import trace4cats.TraceProcess
import zio.test.*
import sttp.client4.*
import sttp.client4.logging.{Logger, LoggingBackend}
import zio.*

object BackendTracerSpec extends ZIOSpecDefault {

  private val successBackend = BackendStub(new RIOMonadAsyncError[Any]).whenAnyRequest
    .thenRespondOk()

  private val ztracerEnv: ZIO[Scope, Nothing, ZEnvironment[InMemorySpanCompleter & ZTracer]] = for {
    result  <- InMemorySpanCompleter.entryPoint(TraceProcess("tapir-trace-interceptor-test"))
    (sc, ep) = result
    tracer  <- InMemorySpanCompleter.toZTracer(ep)
  } yield ZEnvironment(sc).add(tracer)

  val zioLogger = new Logger[Task] {

    override def apply(
      level: logging.LogLevel,
      message: => String,
      exception: Option[Throwable],
      context: Map[String, Any]
    ): Task[Unit] =
      ZIO.log(message)

  }

  def spec: Spec[TestEnvironment & Scope, Any] = suite("Sttp backend tracer")(
    test("Traces requests") {
      for {
        tracer    <- ZIO.service[ZTracer]
        completer <- ZIO.service[InMemorySpanCompleter]
        backend    = BackendTracer(tracer, LoggingBackend(successBackend, zioLogger))
        _ <- basicRequest
               .get(uri"http://host/foo/bar")
               .send(backend)
        logs  <- ZTestLogger.logOutput
        spans <- completer.retrieveCollected
      } yield assertTrue(
        logs.filter(_.message().contains("GET /foo/bar")).forall(_.annotations.contains("X-B3-TraceId")),
        spans.size == 1,
        spans.exists(_.name == "GET /foo/bar"),
        spans.exists(_.attributes.get("remote.service.hostname").exists(_.value.value == "host")),
        spans.exists(_.status.isOk),
        spans.exists(_.attributes.get("resp.status.code").exists(_.value.value == 200))
      )
    }
  ).provide(
    ZLayer.scopedEnvironment[Any](
      ztracerEnv
    )
  )
}
