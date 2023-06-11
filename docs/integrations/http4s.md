---
sidebar_position: 6
title: HTTP4S
---

# HTTP4S 
We provide tracing capabilities for both http4s clients and servers.

## Server
We provide a middleware for http4s that allows you to trace requests and responses. The server looks for trace headers 
in the request and augments the response with trace headers. The following example shows how to trace an http4s server:

```scala mdoc:compile-only
import com.comcast.ip4s.{Host, Port}
import fs2.io.net.Network
import io.kaizensolutions.trace4cats.zio.extras.ZTracer
import io.kaizensolutions.trace4cats.zio.extras.http4s.server.Http4sServerTracer
import org.http4s.dsl.Http4sDsl
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.{HttpApp, HttpRoutes}
import zio.*
import zio.interop.catz.*

type Effect[A] = RIO[ZTracer, A]

def routes: HttpRoutes[Effect] = {
  object dsl extends Http4sDsl[Effect]
  import dsl.*

  HttpRoutes.of {
    case GET -> Root / "hello" / id =>
      ZTracer
        .spanSource()(ZIO.succeed(id).delay(100.millis))
        .flatMap(out => ZTracer.span("sleeper")(ZIO.sleep(100.millis)) *> Ok(s"Hello, $out!"))

    case GET -> Root / "error" =>
      InternalServerError("Oh noes!")
  }
}
val server: RIO[ZTracer & Scope, org.http4s.server.Server] = {
  implicit val networkEffect: Network[Effect] = Network.forAsync[Effect]
  
  ZIO.serviceWithZIO[ZTracer] { tracer =>
    val httpApp: HttpApp[Effect] = Http4sServerTracer.traceRoutes(tracer, routes).orNotFound
    
    ZIO
      .fromEither(Port.fromInt(8080).toRight(new RuntimeException("Invalid Port")))
      .flatMap(port =>
        EmberServerBuilder
          .default[Effect]
          .withHostOption(Host.fromString("localhost"))
          .withPort(port)
          .withHttpApp(httpApp)
          .build
          .toScopedZIO
      )
  }
}
```

Visiting `GET http://localhost:8080/hello/cal` would produce the following trace:
<img width="1018" alt="image" src="https://github.com/kaizen-solutions/trace4cats-zio-extras/assets/14280155/219166d8-9924-4f91-b518-259c103eb8f3"></img>

## Client
We also provide a middleware for http4s clients that allows you to trace requests and responses. For example:
    
```scala mdoc:compile-only
import fs2.io.net.Network
import io.kaizensolutions.trace4cats.zio.extras.ZTracer
import io.kaizensolutions.trace4cats.zio.extras.http4s.client.Http4sClientTracer
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.implicits.*
import zio.*
import zio.interop.catz.*

implicit val fs2NetworkTask: Network[Task] = Network.forAsync[Task]

val tracedClient: ZIO[ZTracer & Scope, Throwable, Client[Task]] =
  for {
    // NOTE: Blocking is necessary to materialize the typeclass instances needed but is not actually used
    // ZTracer is in here because I'm making use of the companion object
    client      <- EmberClientBuilder.default[Task].build.toScopedZIO
    tracer      <- ZIO.service[ZTracer]
    tracedClient = Http4sClientTracer.traceClient(tracer, client)
  } yield tracedClient

val program: ZIO[ZTracer & Scope, Throwable, Unit] = 
  tracedClient.zip(ZIO.service[ZTracer])
    .flatMap { case (client, tracer) =>
    client.get(uri"http://localhost:8080/hello/1") { (response: org.http4s.Response[Task]) =>
      val printBody = response.as[String].flatMap(Console.printLine(_))

      tracer.spanSource() {
        tracer.span("printing-body-hello")(printBody)
      }
    }
}
```

Running the program above would produce the following trace:
<img width="1016" alt="image" src="https://github.com/kaizen-solutions/trace4cats-zio-extras/assets/14280155/2fb4301f-944e-4287-be62-8d8a4ed90f0d"></img>
