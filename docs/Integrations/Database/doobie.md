---
sidebar_position: 3
title: Doobie
---

# Doobie
We provide tracing capabilities for Doobie which is a functional JDBC driver. We wrap the `Transactor` as well as hijack 
the underlying `PreparedStatement` in order to provide detailed debugging information. 

Here is an example of how to set it up:

```scala mdoc:compile-only
import doobie.*
import doobie.syntax.all.*
import io.kaizensolutions.trace4cats.zio.extras.ZTracer
import io.kaizensolutions.trace4cats.zio.extras.doobie.TracedTransactor
import zio.*
import zio.interop.catz.*

val transactorLayer = ZLayer.succeed(
  Transactor.fromDriverManager[Task](
    driver = "org.postgresql.Driver",
    url = "jdbc:postgresql:postgres",
    user = "postgres",
    password = "postgres",
    logHandler = None
  )
)
val tracedTransactorLayer: URLayer[ZTracer, Transactor[Task]] =
  ZLayer.service[ZTracer] ++ transactorLayer >>> TracedTransactor.default

case class City(id: Int, name: String, countryCode: String, district: String, population: Int)

val program: RIO[ZTracer, List[City]] = 
  ZIO.serviceWithZIO[Transactor[Task]] { xa =>
    val startId = 1810
    val endId   = 1830
    
    sql"select id, name, countryCode, district, population from city where id > $startId and id < $endId"
      .query[City]
      .stream
      .transact(xa)
      .debug()
      .compile
      .toList
  }
  .provideSome[ZTracer](tracedTransactorLayer)
```

Here is an example of the trace that is generated:
<img width="1119" alt="image" src="https://github.com/kaizen-solutions/trace4cats-zio-extras/assets/14280155/71e1aa67-461b-4e76-bc6b-ea9eacc74e98"></img>

Have a look at the [example](https://github.com/kaizen-solutions/trace4cats-zio-extras/blob/main/doobie-examples/src/main/scala/doobie/PostgresExampleApp.scala) for a better understanding.