---
sidebar_position: 1
title: Virgil
---

# Virgil
We provide first-class support for Virgil and provide in-depth information when it comes to tracing Cassandra queries 
that are initiated by Virgil even tracing streaming queries. The semantics of a streaming query will cause the span to 
be opened as long as the stream is receiving data from Cassandra.

Here is an example:
```scala mdoc:compile-only
import com.datastax.oss.driver.api.core.CqlSessionBuilder
import io.kaizensolutions.trace4cats.zio.extras.ZTracer
import io.kaizensolutions.virgil.trace4cats.zio.extras.TracedCQLExecutor
import io.kaizensolutions.virgil.*
import io.kaizensolutions.virgil.codecs.*
import io.kaizensolutions.virgil.cql.*
import io.kaizensolutions.virgil.dsl.*
import zio.*

case class Person(id: Int, age: Int, name: String)
object Person {
  // explicit declaration needed for Scala 3
  implicit val personCodec: CqlRowDecoder.Object[Person] = CqlRowDecoder.derive[Person]
}

val insert: ZIO[CQLExecutor, Throwable, Unit] =
  for {
    id   <- Random.nextInt
    age  <- Random.nextInt
    name <- Random.nextUUID
    _ <- InsertBuilder("persons")
      .values(
        "id"   -> id,
        "name" -> name.toString,
        "age"  -> age
      )
      .build
      .executeMutation
  } yield ()

val query: ZIO[CQLExecutor & ZTracer, Throwable, Unit] = 
  ZTracer.span("all-persons") {
    cql"SELECT * FROM persons"
      .query[Person]
      .pageSize(10)
      .execute
      .tap(p => Console.printLine(p.toString))
      .runDrain
}

val program = 
  ZTracer.span("virgil-program")(insert.repeatN(10) *> query)
    .provideSome[CqlSessionBuilder & ZTracer](
      CQLExecutor.live and ZLayer.service[ZTracer] to TracedCQLExecutor.layer
    )
```

This generates the following trace:
<img width="1118" alt="image" src="https://github.com/kaizen-solutions/trace4cats-zio-extras/assets/14280155/021f1dc6-7b3d-4ace-91bf-c34912f2ad78"></img>

Notice that we display the bind markers and the values that are bound to them when executing mutation. 
This is useful when debugging.
