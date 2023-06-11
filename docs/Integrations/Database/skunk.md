---
sidebar_position: 2
title: Skunk
---

# Skunk
We provide tracing capabilities for the high performance Postgres library Skunk. We trace every single method 
(except calls to the `Describe` and `Parse` Cache) provided by `skunk.Session`.

Here is an example of how to trace a Skunk session:

```scala mdoc:compile-only
import cats.effect.kernel.Resource
import fs2.io.net.Network
import io.kaizensolutions.trace4cats.zio.extras.ZTracer
import io.kaizensolutions.trace4cats.zio.extras.skunk.TracedSession
import skunk.*
import zio.*
import zio.interop.catz.*

case class AccessSession(access: RIO[Scope, Session[Task]])

object Skunk {
  import cats.effect.std.Console
  import natchez.Trace.Implicits.noop

  implicit val consoleTask: Console[Task] = Console.make[Task]
  implicit val networkTask: Network[Task] = Network.forAsync[Task]

  // The outer resource is the pool, the inner resource represents taking a session from the pool
  def pooled(tracer: ZTracer): Resource[Task, Resource[Task, Session[Task]]] =
    Session
      .pooled[Task](
        host = "localhost",
        port = 5432,
        user = "postgres",
        password = Some("postgres"),
        database = "postgres",
        max = 8
      )
      .map(_.map(session => TracedSession.make(session, tracer)))

  // The ZIO version of the above
  val zioPooled: URIO[ZTracer & Scope, AccessSession] =
    for {
      tracer <- ZIO.service[ZTracer]
      // Note: Pool construction never fails because sessions in the pool are created lazily
      takeSessionFromPool <- pooled(tracer).toScopedZIO.orDie
    } yield AccessSession(takeSessionFromPool.toScopedZIO)

  val layer: URLayer[ZTracer, AccessSession] = ZLayer.scoped(zioPooled)
}
```

We recommend having a look at the [example](https://github.com/kaizen-solutions/trace4cats-zio-extras/blob/main/skunk-examples/src/main/scala/io/kaizensolutions/trace4cats/zio/extras/skunk/example/SkunkExample.scala)
for the full picture.
