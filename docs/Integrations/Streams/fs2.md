---
sidebar_position: 1
title: FS2
---

# FS2 Streams
In addition to supporting `ZStream` where each element of the stream has its own span, we also support `fs2.Stream` 
in the same way. For example:

```scala mdoc:compile-only
import fs2.Stream
import zio.*
import zio.interop.catz.*
import trace4cats.*
import io.kaizensolutions.trace4cats.zio.extras.fs2.*
import io.kaizensolutions.trace4cats.zio.extras.*

type Effect[A] = RIO[ZTracer, A]

val tracedStream: Stream[Effect, Spanned[Int]] =
    Stream
      .range(1, 100)
      .covary[Effect]
      .evalMap(i => ZTracer.withSpan(s"name-$i")(span => ZIO.succeed((i, span.extractHeaders(ToHeaders.standard)))))
      .traceEachElement("in-begin") { case (_, headers) => headers }
      .mapThrough(_._1)
      .evalMapTraced("Plus 1")(e =>
        ZTracer.span(s"plus 1 for $e")(ZIO.succeed(println(s"Adding ${e} + 1 = ${e + 1}")) *> ZIO.succeed(e + 1))
      )
      .parEvalMapTraced("Plus 2")(8)(e =>
        ZTracer.span(s"plus 2 for $e")(
          ZIO
            .succeed(println(s"Adding ${e} + 2 = ${e + 2}"))
            .delay(500.millis) *>
            ZIO.succeed(e + 2)
        )
      )
      .parEvalMapTraced("Plus 4")(3)(e =>
        ZTracer.span(s"plus 4 for $e")(
          ZTracer.spanSource()(
            ZIO
              .succeed(println(s"Adding ${e} + 4 = ${e + 4}"))
              .delay(1.second)
          ) *> ZIO.succeed(e + 2)
        )
      )

val tracedWorkflow: RIO[ZTracer, Unit] = tracedStream.compile.drain
```

This generates the following set of spans:
<img width="1084" alt="image" src="https://github.com/kaizen-solutions/trace4cats-zio-extras/assets/14280155/ba7fe24f-66ef-43a6-9727-5b455627e34c"></img>
