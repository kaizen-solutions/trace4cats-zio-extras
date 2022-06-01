package io.kaizensolutions.virgil.examples

import com.datastax.oss.driver.api.core.CqlSession
import io.kaizensolutions.trace4cats.zio.extras.ZTracer
import io.kaizensolutions.virgil.*
import io.kaizensolutions.virgil.cql.CqlStringContext
import io.kaizensolutions.virgil.dsl.InsertBuilder
import io.kaizensolutions.virgil.trace4cats.zio.extras.TracedCQLExecutor
import zio.*
import zio.blocking.Blocking
import zio.clock.Clock
import zio.console.*
import zio.duration.*
import zio.random.Random

object ExampleApp extends App {
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

  val dependencies: URLayer[Clock & Blocking, Has[CQLExecutor] & Has[ZTracer]] =
    ZLayer.succeed(CqlSession.builder().withKeyspace("virgil")) ++ JaegarEntrypoint.live >>>
      CQLExecutor.live.orDie ++ ZTracer.layer >+> TracedCQLExecutor.layer

  override def run(args: List[String]): URIO[ZEnv, ExitCode] = {
    val queryProgram: ZIO[Console & Has[CQLExecutor] & Has[ZTracer], Throwable, Unit] =
      ZTracer
        .span("all-persons") {
          cql"SELECT * FROM persons"
            .query[Person]
            .pageSize(10)
            .execute
            .tap(p => putStrLn(p.toString))
            .runDrain
        }

    val insertProgram: RIO[Has[CQLExecutor] & Random, Unit] =
      ZIO.collectAllParN_(2)(
        ZIO.replicate(10)(
          for {
            id   <- random.nextInt
            age  <- random.nextInt
            name <- random.nextUUID
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

    ZTracer
      .span("virgil-program")(
        insertProgram
          .zipPar(queryProgram)
      )
      .repeat(Schedule.spaced(5.seconds))
      .exitCode
      .provideCustomLayer(dependencies)
  }
}
