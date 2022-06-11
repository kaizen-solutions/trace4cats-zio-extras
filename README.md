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
libraryDependencies ++= {
  val org = "com.github.kaizen-solutions.trace4cats-zio-extras"
  Seq(
    org %% "trace4cats-zio-extras-core"     % "<See Latest Release on JitPack>",  // core only
    org %% "trace4cats-zio-extras-zio-http" % "<See Latest Release on JitPack>",  // core + zio http server + client integration
    org %% "trace4cats-zio-extras-http4s"   % "<See Latest Release on JitPack>",  // core + http4s server + client integration
    org %% "trace4cats-zio-extras-sttp"     % "<See Latest Release on JitPack>",  // core + sttp client integration
    org %% "trace4cats-zio-extras-tapir"    % "<See Latest Release on JitPack>",  // core + tapir integration
    org %% "trace4cats-zio-extras-virgil"   % "<See Latest Release on JitPack>",  // core + virgil integration
    org %% "trace4cats-zio-extras-fs2"      % "<See Latest Release on JitPack>"   // core + fs2-streams integration
  )
}
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

`ZTracer` can also be used to trace `ZStream`s in tandem with the `Spanned` datatype where each element of the `ZStream`
has an associated span (i.e. Kafka messages). We have an 
[example](core-examples/src/main/scala/io/kaizensolutions/trace4cats/zio/core/examples/ExampleApp.scala) that will get 
you started.

**Note:** There are some exceptions to this rule like when sending very large traces to Jaeger for example where the 
span size can be larger than what the collector accepts. To work around this, you will have to adjust the batch size 
when configuring a `SpanCompleter` and ensure that you perform local testing and ensuring your application continues to 
function to avoid these edge cases. 

## FS2 integration

In addition to supporting `ZStream` where each element of the stream has its own span, we also support `fs2.Stream` in 
the same way. There are a few requirements on the type of ZIO effect that can be used with `fs2.Stream` since we need to 
materialize the `Async` and `Concurrent` typeclasses from `cats-effect` and as a result we require the ZIO environment 
contain `Clock`, `Blocking` and `ZTracer` in order to perform spans. In addition, the error type must be fixed to 
`Throwable` otherwise these typeclasses cannot be derived (from the `interop-cats` library). We have an 
[example](fs2-examples/src/main/scala/io/kaizensolutions/trace4cats/zio/extras/fs2/ExampleApp.scala) to help you get 
started, and it's almost the same as the `ZStream` example.

## HTTP4S integration

Whilst Trace4Cats does provide a 1st party integration for ZIO, you have to avoid using the ZIO environment directly 
because of the way the `Provide` typeclass works with ZIO. We work around this by directly implementing an HTTP4S 
middleware which allows you to wrap either any `HttpRoutes[ZIO[R, E, *]]` or any `HttpApp[ZIO[R, E, *]]`  
(provided zio-interop-cats will give you typeclass instances for your selected effect type). The integration follows 
very closely to the Trace4Cats HTTP4S integration and is intended to function as a drop-in replacement.

Here's an example:

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
    (NewRelicEntrypoint.live >>> ZTracer.layer) ++ Db.live
  )
```

The example generates the following trace in New Relic:
![http4s-tracing-example](https://user-images.githubusercontent.com/14280155/165553739-3fd112db-e2fe-4f8e-905b-721ceeacd687.png)

We provide integrations for both the server and client.

## ZIO-HTTP integration

We provide an integration for zio-http's `HttpApp` datatype with similar semantics to the HTTP4S integration with some
slight differences with the amount of information available in the trace due zio-http having a different API. 

The easiest way to trace a ZIO HTTP application is to use the `trace` middleware. Here is an example:

```scala
package io.kaizensolutions.trace4cats.zio.extras.ziohttp.examples

import io.kaizensolutions.trace4cats.zio.extras.ZTracer
import io.kaizensolutions.trace4cats.zio.extras.ziohttp.server.ZioHttpServerTracer.trace
import zhttp.http.*
import zhttp.service.Server
import zio.*
import zio.clock.Clock
import zio.duration.*
import zio.random.Random

object ExampleServerApp extends App {
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
    Server
      .start(8080, app @@ trace)  // tracing is implemented as a middleware for ZIO HTTP
      .exitCode
      .provideCustomLayer(
        (JaegarEntrypoint.live >>> ZTracer.layer) ++ Db.live
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
  val tracerLayer: URLayer[Clock & Blocking, Has[ZTracer]]     = NewRelicEntrypoint.live >>> ZTracer.layer
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

## Tapir Integration

We provide preliminary support for tracing Tapir server endpoints, users of the API have to explicitly perform additional 
mappings to extract headers and not enough information is present to gather status codes. If you can, just trace the 
underlying interpreters instead as they provide greater insights and more information in traces.

Here is a small example:

```scala
  def countCharacters(tracer: ZTracer)(in: Request): UIO[Either[NoCharacters, Int]] = {
    val l = in.input.length
    val out = tracer.spanSource() {
      if (l > 0) ZIO.succeed(l)
      else ZIO.fail(NoCharacters("Please supply at least 1 character to count"))
    }

    out.either
  }

  val countCharactersEndpoint: Endpoint[Unit, Request, NoCharacters, Int, Any] =
    endpoint.post
      .in(stringBody(Charset.defaultCharset()))
      .in(headers)
      .mapIn(raw => Request(raw._1, Headers(raw._2)))(r => (r.input, r.headers.headers.toList))
      .errorOut(statusCode(StatusCode.BadRequest).and(jsonBody[NoCharacters]))
      .out(plainBody[Int])

  def serverEndpoint(tracer: ZTracer): ServerEndpoint.Full[Unit, Unit, Request, NoCharacters, Int, Any, Task] =
    countCharactersEndpoint.serverLogic(countCharacters(tracer))

  def tracedServerEndpoint(tracer: ZTracer): ServerEndpoint.Full[Unit, Unit, Request, NoCharacters, Int, Any, Task] =
    TapirServerTracer
      .traceEndpoint[Request, NoCharacters, Int, Any, Any, Throwable](
        tracer = tracer,
        serverEndpoint = serverEndpoint(tracer),
        extractRequestHeaders = _.headers,
        extractResponseHeaders = _ => Headers(Nil)
      )

  val program =
    ZIO.runtime[Clock & Blocking].flatMap { implicit rts =>
      val server =
        for {
          tracer  <- ZIO.service[ZTracer]
          endpoint = tracedServerEndpoint(tracer)
          httpApp  = Http4sServerInterpreter[Task]().toRoutes(endpoint).orNotFound
          server = BlazeServerBuilder[Task]
            .bindHttp(8080, "localhost")
            .withHttpApp(httpApp)
        } yield server
        
    server.flatMap(_.resource.toManagedZIO.useForever)
  }
```
## [Virgil](https://github.com/kaizen-solutions/virgil) integration

We provide first-class support for Virgil and provide in-depth information when it comes to tracing Cassandra queries 
that are initiated by Virgil even tracing streaming queries. The semantics of a streaming query will cause the span to
be opened as long as the stream is receiving data from Cassandra.

Here is a small example:

```scala
  val dependencies: URLayer[Clock & Blocking, Has[CQLExecutor] & Has[ZTracer]] =
    ZLayer.succeed(CqlSession.builder().withKeyspace("virgil")) ++ JaegarEntrypoint.live >>>
      CQLExecutor.live.orDie ++ ZTracer.layer >+> TracedCQLExecutor.layer

  val queryProgram: ZIO[Console & Has[CQLExecutor] & Has[ZTracer], Throwable, Unit] = 
    ZTracer.span("all-persons") {
      cql"SELECT * FROM persons"
        .query[Person]
        .pageSize(10)
        .execute
        .tap(p => putStrLn(p.toString))
        .runDrain
    }

  val insertProgram: RIO[Has[CQLExecutor] & Random, Unit] = ZIO.collectAllParN_(2)(
    ZIO.replicate(10)(
      for {
        id   <- random.nextInt
        age  <- random.nextInt
        name <- random.nextUUID
        _ <- InsertBuilder("persons")
          .values(
            "id"   -> id,
            "name" -> name.toString,
            "age"  -> age
          )
          .build
          .executeMutation
      } yield ()
    )
  )

  ZTracer
    .span("virgil-program")(insertProgram.zipPar(queryProgram))
    .repeat(Schedule.spaced(5.seconds))
    .exitCode
    .provideCustomLayer(dependencies)
```

The program above simultaneously inserts 10 persons (2 in parallel at a time) and queries them.  
Here's an example of the trace generated by the program in Jaegar:
![image](https://user-images.githubusercontent.com/14280155/170582448-99598075-aa15-486f-a923-c4157cdc853b.png)

If you make a mistake, let's say you had incorrectly named your Person datatype in Scala and there's a mismatch with 
the database (let's say you did `case class Person(a: Int, age: Int, name: String)`) then you'll get more informative 
error details in the span:
![image](https://user-images.githubusercontent.com/14280155/170741667-2fba1d9f-480b-418d-b0fb-140118232a9f.png)

## Local setup

We include a local setup of Jaegar along with its UI for easy testing. Bring it up using `docker-compose up`. 
All the examples in the repository connect to it, so you can have a playground to try out the examples.

Visit [the Jaegar UI](http://localhost:16686) and look through the traces:

![Jaegar-UI-example](https://user-images.githubusercontent.com/14280155/165848829-31d39798-bb69-480b-aed9-72c7707df80d.png)

Alternatively, if you prefer using New Relic, you can to connect to it instead:
```sbt
libraryDependencies ++= Seq(
  "io.janstenpickle" %% "trace4cats-newrelic-http-exporter" % "0.13.1",
  "org.http4s"       %% "http4s-blaze-client"               % "0.23.11"
)
```

```scala
import io.janstenpickle.trace4cats.inject.EntryPoint
import io.janstenpickle.trace4cats.kernel.SpanSampler
import io.janstenpickle.trace4cats.model.TraceProcess
import io.janstenpickle.trace4cats.newrelic.{Endpoint, NewRelicSpanCompleter}
import io.kaizensolutions.trace4cats.zio.extras.*
import org.http4s.blaze.client.BlazeClientBuilder
import zio.blocking.Blocking
import zio.clock.Clock
import zio.interop.catz.*
import zio.*

object NewRelicEntrypoint {
  val live: URLayer[Clock & Blocking, Has[ZEntryPoint]] =
    ZLayer
      .fromManaged(
        entryPoint(TraceProcess("zio-http-example-app"))
      )
      .orDie

  def entryPoint(process: TraceProcess): RManaged[Clock & Blocking, ZEntryPoint] =
    ZManaged.runtime[Clock & Blocking].flatMap { implicit rts =>
      (for {
        client <- BlazeClientBuilder[Task].resource
        completer <- NewRelicSpanCompleter[Task](
                       client = client,
                       process = process,
                       apiKey = "136bcc149f079eac2b2da7663aba7df6FFFFNRAL", // Insert your New Relic API key here
                       endpoint = Endpoint.US
                     )
      } yield EntryPoint[Task](SpanSampler.always[Task], completer)).toZManaged
    }
}
```