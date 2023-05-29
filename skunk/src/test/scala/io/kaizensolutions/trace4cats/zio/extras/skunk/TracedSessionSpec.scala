package io.kaizensolutions.trace4cats.zio.extras.skunk

import fs2.io.net.Network
import io.kaizensolutions.trace4cats.zio.extras.skunk.Database.TakeSession
import io.kaizensolutions.trace4cats.zio.extras.{InMemorySpanCompleter, ZTracer}
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import skunk.Session
import skunk.codec.all.*
import skunk.syntax.all.*
import zio.*
import zio.test.*
import zio.interop.catz.*

object TracedSessionSpec extends ZIOSpecDefault {
  override def spec: Spec[TestEnvironment, Any] =
    suite("Traced Session specification")(
      test("parameters")(
        ZIO.scoped(
          for {
            take    <- ZIO.service[TakeSession]
            session <- take.access
            _       <- session.parameters.get
            sc      <- ZIO.service[InMemorySpanCompleter]
            spans   <- sc.retrieveCollected
          } yield assertTrue(spans.length == 1, spans.exists(_.name == "skunk.parameters.get"))
        )
      ),
      test("parameter")(
        ZIO.scoped(
          for {
            take    <- ZIO.service[TakeSession]
            session <- take.access
            _       <- session.parameter("server_version").head.compile.last
            sc      <- ZIO.service[InMemorySpanCompleter]
            spans   <- sc.retrieveCollected
          } yield assertTrue(spans.length == 1, spans.exists(_.name == "skunk.parameter.server_version"))
        )
      ),
      test("transactionStatus")(
        ZIO.scoped(
          for {
            take    <- ZIO.service[TakeSession]
            session <- take.access
            _       <- session.transactionStatus.get
            sc      <- ZIO.service[InMemorySpanCompleter]
            spans   <- sc.retrieveCollected
          } yield assertTrue(spans.length == 1, spans.exists(_.name == "skunk.transactionStatus.get"))
        )
      ),
      test("execute")(
        ZIO.scoped(
          for {
            take       <- ZIO.service[TakeSession]
            session    <- take.access
            createTable = sql"CREATE TABLE abc (name varchar primary key)".command
            _          <- session.execute(createTable)
            sc         <- ZIO.service[InMemorySpanCompleter]
            spans      <- sc.retrieveCollected
          } yield assertTrue(spans.length == 1, spans.exists(_.name == createTable.sql))
        )
      ),
      test("unique")(
        ZIO.scoped {
          for {
            take    <- ZIO.service[TakeSession]
            session <- take.access
            query    = sql"SELECT 42".query(int4)
            _       <- session.unique(query)
            sc      <- ZIO.service[InMemorySpanCompleter]
            spans   <- sc.retrieveCollected
          } yield assertTrue(spans.length == 1, spans.exists(_.name == query.sql))
        }
      ),
      test("option")(
        ZIO.scoped {
          for {
            take    <- ZIO.service[TakeSession]
            session <- take.access
            query    = sql"select schemaname, relname from pg_stat_sys_tables limit 0".query(name ~ name)
            _       <- session.option(query)
            sc      <- ZIO.service[InMemorySpanCompleter]
            spans   <- sc.retrieveCollected
          } yield assertTrue(spans.length == 1, spans.exists(_.name == query.sql))
        }
      ),
      test("stream")(
        ZIO.scoped {
          for {
            take    <- ZIO.service[TakeSession]
            session <- take.access
            query    = sql"select schemaname, relname from pg_stat_sys_tables".query(name ~ name)
            _       <- session.stream(query)(args = skunk.Void, chunkSize = 128).compile.drain
            sc      <- ZIO.service[InMemorySpanCompleter]
            spans   <- sc.retrieveCollected
          } yield assertTrue(spans.length == 1, spans.exists(_.name == query.sql))
        }
      ),
      test("prepare + stream")(
        ZIO.scoped {
          for {
            take    <- ZIO.service[TakeSession]
            session <- take.access
            query    = sql"select schemaname, relname from pg_stat_sys_tables".query(name ~ name)
            pq      <- session.prepare(query)
            _       <- pq.stream(args = skunk.Void, chunkSize = 128).compile.drain
            sc      <- ZIO.service[InMemorySpanCompleter]
            spans   <- sc.retrieveCollected
          } yield assertTrue(
            spans.length == 1,
            spans.exists(_.name == query.sql),
            spans.exists(_.attributes.contains("prepared"))
          )
        }
      )
    )
      .provideShared(Database.live, InMemorySpanCompleter.layer("skunk-test")) @@
      TestAspect.sequential
}
object Database {
  import cats.effect.std.Console
  import natchez.Trace as NatchezTrace

  final case class TakeSession(access: ZIO[Scope, Throwable, Session[Task]]) extends AnyVal

  implicit val ceConsoleTask: Console[Task]         = Console.make[Task]
  implicit val natchezTraceTask: NatchezTrace[Task] = NatchezTrace.Implicits.noop[Task]
  implicit val fs2NetworkTask: Network[Task]        = Network.forAsync[Task]

  def sessionPool(ep: EmbeddedPostgres): URIO[Scope & ZTracer, TakeSession] = {
    ZIO.serviceWithZIO[ZTracer](tracer =>
      Session
        .pooled[Task](
          host = "localhost",
          port = ep.getPort,
          user = "postgres",
          password = None,
          database = "postgres",
          max = 8
        )
        .toScopedZIO
        .orDie
        .map(_.toScopedZIO.map(TracedSession.make(_, tracer)))
        .map(TakeSession(_))
    )
  }

  val live: RLayer[ZTracer, TakeSession] = {
    val postgres: ZIO[Scope, Throwable, EmbeddedPostgres] =
      ZIO.acquireRelease(
        ZIO.attempt(
          EmbeddedPostgres
            .builder()
            .setCleanDataDirectory(false)
            .start()
        )
      )(ep => ZIO.attempt(ep.close()).ignoreLogged)

    ZLayer.scoped(postgres.flatMap(sessionPool))
  }
}
