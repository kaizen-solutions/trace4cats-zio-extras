---
sidebar_position: 4
title: STTP
---

# STTP
We provide an integration for sttp clients that support ZIO's Task.

```scala mdoc:compile-only
import io.kaizensolutions.trace4cats.zio.extras.ZTracer
import io.kaizensolutions.trace4cats.zio.extras.sttp.SttpBackendTracer
import sttp.capabilities
import sttp.capabilities.zio.ZioStreams
import sttp.client3.*
import sttp.client3.httpclient.zio.HttpClientZioBackend
import zio.*

type SttpClient = SttpBackend[Task, ZioStreams & capabilities.WebSockets]

val tracedBackend: URIO[Scope & ZTracer, SttpClient] =
  (for {
    tracer  <- ZIO.service[ZTracer]
    backend <- HttpClientZioBackend.scoped()
  } yield SttpBackendTracer(tracer, backend)).orDie
```

Simply use the `tracedBackend` as your STTP client like normal and all outgoing requests will be traced.
