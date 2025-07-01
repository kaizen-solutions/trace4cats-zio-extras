---
sidebar_position: 4
title: STTP4
---

# STTP4
We provide an integration for sttp4 clients that support ZIO's Task.

```scala mdoc:compile-only
import io.kaizensolutions.trace4cats.zio.extras.ZTracer
import io.kaizensolutions.trace4cats.zio.extras.sttp4.BackendTracer
import sttp.client4.*
import sttp.client4.httpclient.zio.HttpClientZioBackend
import zio.*

val tracedBackend: URIO[Scope & ZTracer, Backend[Task]] =
  (for {
    tracer  <- ZIO.service[ZTracer]
    backend <- HttpClientZioBackend.scoped()
  } yield BackendTracer(tracer, backend)).orDie
```

Simply use the `tracedBackend` as your STTP4 client like normal and all outgoing requests will be traced.
