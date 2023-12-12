---
sidebar_position: 3
title: Tapir
---

# Tapir
In this section, we'll walk you through our module that enables you to trace Tapir endpoints in your application 
using interceptors.

```scala mdoc:compile-only
import io.kaizensolutions.trace4cats.zio.extras.ZTracer
import io.kaizensolutions.trace4cats.zio.extras.tapir.TraceInterceptor
import sttp.tapir.*
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.http4s.{Http4sServerInterpreter, Http4sServerOptions}
import sttp.model.StatusCode
import zio.*
import zio.interop.catz.*

final class CountCharactersEndpoint(tracer: ZTracer) {
  private val countCharactersEndpoint: Endpoint[Unit, String, Unit, Int, Any] =
    endpoint.post
      .in("count" / "characters")
      .in(stringBody)
      .errorOut(statusCode(StatusCode.BadRequest))
      .out(plainBody[Int])

  private def countCharactersServerLogic(raw: String): IO[Unit, Int] = tracer.spanSource() {
    if (raw.isEmpty) ZIO.unit.flip
    else ZIO.succeed(raw.length)
  }

  val countCharactersServerEndpoint: ServerEndpoint.Full[Unit, Unit, String, Unit, Int, Any, Task] =
    countCharactersEndpoint.serverLogic { raw => countCharactersServerLogic(raw).either }
}
  
val http4sApp =
  for {
    tracer     <- ZIO.service[ZTracer]
    interceptor = TraceInterceptor(tracer)
    endpoint    = new CountCharactersEndpoint(tracer)
    httpApp     = Http4sServerInterpreter[Task](
      Http4sServerOptions
        .default[Task]
        .prependInterceptor(interceptor)
    ).toRoutes(endpoint.countCharactersServerEndpoint).orNotFound
  } yield httpApp

```

## Recommendation
Use the Tapir interceptor instead of directly integrating with the server interpreters.
