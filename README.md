# Trace4Cats ZIO Extras

![Build Status](https://github.com/kaizen-solutions/trace4cats-zio-extras/actions/workflows/ci.yml/badge.svg)
[![Jitpack](https://jitpack.io/v/kaizen-solutions/trace4cats-zio-extras.svg)](https://jitpack.io/#kaizen-solutions/trace4cats-zio-extras)

Provides a variety of ZIO tracing integrations (with http4s and zio-http) and abstractions (`ZTracer`) for use with 
Trace4Cats without making use of the typeclasses inside trace4cats (eg. `Provide`, etc.) and instead uses the ZIO 
environment (`R`) and `FiberRef`s directly to call the underlying Trace4Cats APIs in order to provide a better ZIO API 
experience for the user.

## Getting started
```scala
resolvers += "jitpack" at "https://jitpack.io"
libraryDependencies ++= Seq(
  "com.github.kaizen-solutions.trace4cats-zio-extras" %% "trace4cats-zio-extras" % "<See Latest Release on JitPack>"
)
```


Take a look at the example projects in this repository in order to get started.

## ZTracer

`ZTracer` is a replacement for the Trace4Cats `Trace` typeclass written in a way to be used with ZIO to play nicely with
the usage of the ZIO environment and errors.

```scala
import zio.*
import io.kaizensolutions.trace4cats.zio.extras.ZTracer

val nestedTrace: URIO[Has[ZTracer], Unit] = {
  //      parent
  //      /    \
  //    child1  child2
  //    /
  // grandchild
  ZTracer.span("parent") {
    ZTracer
      .span("child1")(ZTracer.span("grandchild")(UIO.unit))
      .zipParLeft(ZTracer.span("child2")(UIO.unit))
  }
}
```

Notice that using the `ZTracer` abstraction cannot fail; meaning if you mis-configure the tracing configuration, your 
application will continue to function but traces will not be reported.

## HTTP4S integration

Whilst Trace4Cats does provide a 1st party integration for ZIO, you have to avoid using the ZIO environment directly 
because of the way the `Provide` typeclass works with ZIO. We work around this by directly implementing an HTTP4S 
middleware which allows you to wrap either any `HttpRoutes[ZIO[R, E, *]]` or any `HttpApp[ZIO[R, E, *]]`  
(provided zio-interop-cats will give you typeclass instances for your selected effect type). The integration follows 
very closely to the Trace4Cats HTTP4s integration and is intended to function as a drop-in replacement.

Here's an example:

```scala

```scala
import io.kaizensolutions.trace4cats.zio.extras.ZTracer
import io.kaizensolutions.trace4cats.zio.extras.http4s.server.Http4sServerTracer
import org.http4s.blaze.server.BlazeServerBuilder
import org.http4s.dsl.Http4sDsl
import org.http4s.{HttpApp, HttpRoutes}
import zio.*
import zio.blocking.Blocking
import zio.clock.Clock
import zio.duration.durationInt
import zio.interop.catz.*

type Effect[A] = ZIO[Clock & Blocking & Has[Db] & Has[ZTracer], Throwable, A]

def routes: HttpRoutes[Effect] = {
  object dsl extends Http4sDsl[Effect]
  import dsl.*

  HttpRoutes.of {

    case GET -> Root / "hello" / id =>
      val myId = Try(id.toInt).getOrElse(1)
      ZTracer
        .spanSource()(Db.get(myId))
        .flatMap(out => ZTracer.span("sleeper")(ZIO.sleep(100.millis)) *> Ok(s"Hello, $out!"))

    case GET -> Root / "error" =>
      InternalServerError("Oh noes!")
  }
}

ZManaged
  .runtime[Clock & Blocking]
  .flatMap { implicit rts =>
    val _ = rts
    ZManaged.service[ZTracer].flatMap { tracer =>
      val httpApp: HttpApp[Effect] = Http4sServerTracer.traceRoutes(tracer, routes).orNotFound

      BlazeServerBuilder[Effect]
        .bindHttp(8080, "localhost")
        .withHttpApp(httpApp)
        .resource
        .toManagedZIO
    }
  }
  .useForever
  .exitCode
  .provideCustomLayer(
    (NewRelicEntrypoint.live >>> ZTracer.live) ++ Db.live
  )
```

The example generates the following trace in New Relic:
![http4s-tracing-example](https://user-images.githubusercontent.com/14280155/165553739-3fd112db-e2fe-4f8e-905b-721ceeacd687.png)

We provide integrations for both the server and client.

## ZIO-HTTP integration

We provide an integration for zio-http's `HttpApp` datatype with similar semantics to the http4s integration with some
slight differences with the amount of information available in the trace due zio-http having a different API. 

Here is an example:

```scala
import io.kaizensolutions.trace4cats.zio.extras.ZTracer
import io.kaizensolutions.trace4cats.zio.extras.ziohttp.ZioHttpServerTracer
import zhttp.http.*
import zhttp.service.Server
import zio.*
import zio.clock.Clock
import zio.duration.*
import zio.random.Random

object ExampleApp extends App {
  val app: Http[Clock & Random & Has[Db] & Has[ZTracer], Throwable, Request, Response] =
    Http.collectZIO[Request] {

      case Method.GET -> !! / "plaintext" =>
        ZTracer.withSpan("plaintext-fetch-db") { span =>
          for {
            sleep <- random.nextIntBetween(1, 3)
            _     <- span.put("sleep-duration.seconds", sleep)
            _     <- ZTracer.spanSource()(ZIO.sleep(sleep.seconds) *> Db.get(sleep))
          } yield Response
            .text(sleep.toString)
            .updateHeaders(_.addHeader("custom-header", sleep.toString))
            .setStatus(Status.Ok)
        }

      case Method.GET -> !! / "fail" =>
        ZIO.fail(new RuntimeException("Error"))

      case Method.GET -> !! / "bad_gateway" =>
        ZIO.succeed(Response.status(Status.BadGateway))
    }

  override def run(args: List[String]): URIO[ZEnv, ExitCode] =
    ZIO
      .service[ZTracer]
      .flatMap { tracer =>
        val tracedApp = ZioHttpServerTracer.traceApp(tracer, app)
        Server.start(8080, tracedApp)
      }
      .exitCode
      .provideCustomLayer(
        (NewRelicEntrypoint.live >>> ZTracer.live) ++ Db.live
      )
}
```

The example generates the following trace in New Relic when `/plaintext` is queried:
![newrelic-zio-http-trace](https://user-images.githubusercontent.com/14280155/165549664-b0608ec3-1d6a-47c1-8e0b-f46260fc6b22.png)

We provide integrations for both the server and client.

## STTP client integration

We provide an integration for sttp clients that support ZIO's `Task`.

```scala
import io.kaizensolutions.trace4cats.zio.extras.ZTracer
import io.kaizensolutions.trace4cats.zio.extras.sttp.SttpBackendTracer
import sttp.capabilities
import sttp.capabilities.zio.ZioStreams
import sttp.client3.httpclient.zio.HttpClientZioBackend
import zio.*
import zio.blocking.Blocking
import zio.clock.Clock

type SttpClient = SttpBackend[Task, ZioStreams & capabilities.WebSockets]

val tracedBackendManaged: URManaged[Has[ZTracer], SttpClient] =
  (for {
    tracer  <- ZManaged.service[ZTracer]
    backend <- HttpClientZioBackend.managed()
  } yield SttpBackendTracer(tracer, backend)).orDie

val dependencies: URLayer[Clock & Blocking, Has[ZTracer] & Has[SttpClient]] = {
  val tracerLayer: URLayer[Clock & Blocking, Has[ZTracer]]     = NewRelicEntrypoint.live >>> ZTracer.live
  val sttpBackendLayer: URLayer[Has[ZTracer], Has[SttpClient]] = tracedBackendManaged.toLayer
  tracerLayer >+> sttpBackendLayer
}
```

We can now used the traced client:
```scala
import sttp.client3.*

ZIO
  .service[SttpClient]
  .flatMap { client =>
    val sayHello =
      client
        .send(basicRequest.get(uri"http://localhost:8080/hello/1"))
        .tap(r => ZTracer.spanSource()(ZIO.debug(r.statusText) *> ZIO.debug(r.body)))

    val error =
      client
        .send(basicRequest.get(uri"http://localhost:8080/error"))
        .tap(r =>
          ZTracer.span("headers-error")(
            ZTracer.span("printing-body-status-error")(ZIO.debug(r.statusText) *> ZIO.debug(r.body))
          )
        )

    ZTracer
      .span("sttp-client-hello-error-par") {
        sayHello
          .zipPar(error)
          .repeat(Schedule.recurs(10) *> Schedule.spaced(1.second))
      }
  }
```

This would generate the following trace in New Relic:
![sttp-client-example-trace](https://user-images.githubusercontent.com/14280155/165789418-7dd3fcc7-cca1-4278-948f-09bfd214f7ec.png)