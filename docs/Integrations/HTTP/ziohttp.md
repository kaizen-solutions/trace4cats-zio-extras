---
sidebar_position: 2
title: ZIO HTTP
---

# ZIO HTTP
We provide integrations for both the server and client side of ZIO HTTP.

## Server
We provide a middleware for zio-http that allows you to trace requests and responses. Similar to the http4s integration,
the middleware looks for trace headers in the request and augments the response with trace headers. 

Here's an example:
```scala mdoc:compile-only
import io.kaizensolutions.trace4cats.zio.extras.ZTracer
import io.kaizensolutions.trace4cats.zio.extras.ziohttp.server.ZioHttpServerTracer.trace
import zio.*
import zio.http.*

val http =
  Routes(
    Method.GET / "plaintext" -> handler(
      ZTracer.withSpan("plaintext-fetch-db") { span =>
        for {
          sleep <- Random.nextIntBetween(1, 3)
          _     <- span.put("sleep-duration.seconds", sleep)
          _     <- ZIO.logInfo("HELLO")
          _     <- ZTracer.spanSource()(ZIO.sleep(sleep.seconds))
        } yield Response
          .text(sleep.toString)
          .updateHeaders(_.addHeader("custom-header", sleep.toString))
          .status(Status.Ok)
      }
    ),
    Method.GET / "fail"        -> handler(ZIO.fail(new RuntimeException("Error"))),
    Method.GET / "bad_gateway" -> handler(ZIO.succeed(Response.status(Status.BadGateway)))
  )

val app: HttpApp[ZTracer] =
  http.handleError(error => Response.text(error.getMessage).status(Status.InternalServerError)).toHttpApp

val tracedApp: HttpApp[ZTracer] = app @@ trace(enrichLogs = true) // the tracing middleware
```

Notice how `enrichLogs` is set to true, this will start augmenting the log context with trace header information. You 
can also customize the trace headers and change the name of the span in order to reduce cardinality. 

Here is an example of a trace when you hit the `/plaintext` endpoint:
<img width="1114" alt="image" src="https://github.com/kaizen-solutions/trace4cats-zio-extras/assets/14280155/3ea7da5f-5ceb-43d5-a2d5-c51c88d776c9"></img>

Accompanying this trace is the following log:
```
10:39:57.712 [KQueueEventLoopGroup-2-2] [b3=5e66b278533ccb68db1610c56b464b31-3fd736b93350db66-1, X-B3-Sampled=1, X-B3-TraceId=5e66b278533ccb68db1610c56b464b31, X-B3-SpanId=3fd736b93350db66, traceparent=00-5e66b278533ccb68db1610c56b464b31-3fd736b93350db66-01] INFO  i.k.t.z.e.z.e.E.http - HELLO
```
Notice how the log is enriched with the trace headers.

## Client
We provide a traced client that calls out the zio-http client. It will automatically propagate the trace headers when making
requests. Since ZIO HTTP is still going through some changes, the client library is an unstable API. Our goal is to 
eventually facade the `Client` itself instead of what we currently do today.

Here's an example:
```scala mdoc:compile-only
import io.kaizensolutions.trace4cats.zio.extras.ZTracer
import io.kaizensolutions.trace4cats.zio.extras.ziohttp.client.*
import zio.*

val reqPlainText =
  ZioHttpClientTracer
    .tracedRequest("http://localhost:8080/plaintext")
    .tap(response => ZTracer.spanSource()(response.body.asString.flatMap(Console.printLine(_))))

val reqFail =
  ZioHttpClientTracer
    .tracedRequest("http://localhost:8080/fail")
    .tap(response => response.body.asString.flatMap(Console.printLine(_)))

val reqBadGateway =
  ZioHttpClientTracer
    .tracedRequest("http://localhost:8080/bad_gateway")
    .tap(response => response.body.asString.flatMap(Console.printLine(_)))
```

Here is an example of the traces you can expect:
<img width="1122" alt="image" src="https://github.com/kaizen-solutions/trace4cats-zio-extras/assets/14280155/8028bdc0-1246-4bf2-9f56-5f08763059f5"></img>
