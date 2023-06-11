---
sidebar_position: 3
title: Tapir
---

# Tapir
In this section, we'll walk you through our module that enables you to trace Tapir endpoints. 
This process requires each Tapir endpoint to reveal headers that hold trace information.

```scala mdoc:compile-only
import io.kaizensolutions.trace4cats.zio.extras.ZTracer
import io.kaizensolutions.trace4cats.zio.extras.tapir.TapirServerTracer
import sttp.tapir.*
import sttp.tapir.server.ServerEndpoint
import sttp.model.{Header, Headers, StatusCode}
import zio.*

final class CountCharactersEndpoint(tracer: ZTracer) {
  private val countCharactersEndpoint: Endpoint[Unit, (String, List[Header]), Unit, Int, Any] =
    endpoint.post
      .in("count" / "characters")
      .in(stringBody)
      .in(headers)
      .errorOut(statusCode(StatusCode.BadRequest))
      .out(plainBody[Int])

  private def countCharactersServerLogic(raw: String): IO[Unit, Int] = tracer.spanSource() {
    if (raw.isEmpty) ZIO.unit.flip
    else ZIO.succeed(raw.length)
  }

  val countCharactersServerEndpoint: ServerEndpoint.Full[Unit, Unit, (String, List[Header]), Unit, Int, Any, Task] =
    countCharactersEndpoint.serverLogic { case (raw, _) => countCharactersServerLogic(raw).either }
    
  val tracedEndpoint: ServerEndpoint.Full[Unit, Unit, (String, List[Header]), Unit, Int, Any, Task] = 
    TapirServerTracer.traceEndpoint(
      tracer = tracer,
      serverEndpoint = countCharactersServerEndpoint,
      extractRequestHeaders = (input: (String, Seq[Header])) => Headers(input._2.toList),
      extractResponseHeaders = (_: Int) => Headers(Nil)
    )
}
```

The tracedEndpoint can then be used when compiling your Tapir endpoints down to the server's representation.

## Recommendation
As you've probably noticed, revealing headers for each Tapir endpoint can be a bit cumbersome. 
Hence, we recommend a more streamlined approach:

Instead of managing headers directly in Tapir, try compiling the Tapir endpoints first, and then accessing the 
underlying server integration (like [HTTP4S](../HTTP/http4s.md) & [ZIO HTTP](../HTTP/ziohttp.md)). 
This approach tends to be more straightforward.
