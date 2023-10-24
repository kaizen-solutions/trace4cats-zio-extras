package io.kaizensolutions.virgil.examples

import com.datastax.oss.driver.api.core.CqlSession
import io.kaizensolutions.trace4cats.zio.extras.ZTracer
import io.kaizensolutions.virgil.*
import io.kaizensolutions.virgil.cql.CqlStringContext
import io.kaizensolutions.virgil.dsl.InsertBuilder
import io.kaizensolutions.virgil.trace4cats.zio.extras.TracedCQLExecutor
import zio.*
import zio.Console.printLine

object ExampleApp extends ZIOAppDefault {
  /*
    CREATE KEYSPACE IF NOT EXISTS virgil WITH REPLICATION = {
      'class': 'SimpleStrategy',
      'replication_factor': 1
    };
    USE virgil;
    CREATE TABLE persons(
      id INT,
      name TEXT,
      age INT,
      PRIMARY KEY (id)
    );
   */

  val run: ZIO[Any & ZIOAppArgs & Scope, Any, Any] = {
    val queryProgram: ZIO[CQLExecutor & ZTracer, Throwable, Unit] =
      ZTracer
        .span("all-persons") {
          cql"SELECT * FROM persons"
            .query[Person]
            .pageSize(10)
            .execute
            .tap(p => printLine(p.toString))
            .runDrain
        }

    val insertProgram: RIO[CQLExecutor, Unit] = {
      ZIO.withParallelism(2) {
        ZIO.collectAllParDiscard(
          ZIO.replicate(10)(
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
          )
        )
      }
    }

    ZTracer
      .span("virgil-program")(insertProgram.zipPar(queryProgram))
      .repeat(Schedule.spaced(5.seconds))
      .provide(
        ZLayer.succeed(CqlSession.builder().withKeyspace("virgil")),
        JaegerEntrypoint.live,
        ZTracer.layer,
        CQLExecutor.live.orDie ++ ZLayer.service[ZTracer] >>> TracedCQLExecutor.layer
      )
  }
}
