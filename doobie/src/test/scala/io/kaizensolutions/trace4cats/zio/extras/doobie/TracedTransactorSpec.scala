package io.kaizensolutions.trace4cats.zio.extras.doobie

import doobie.Transactor
import doobie.implicits.*
import doobie.util.ExecutionContexts
import io.kaizensolutions.trace4cats.zio.extras.{InMemorySpanCompleter, ZTracer}
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.interop.catz.*
import zio.test.*
import zio.{Task, ZIO, ZLayer}

import javax.sql.DataSource

object TracedTransactorSpec extends ZIOSpecDefault {

  val xa: ZLayer[DataSource, Nothing, Transactor[Task]] = ZLayer.fromFunction(
    Transactor
      .fromDataSource[Task]
      .apply[DataSource](
        _,
        ExecutionContexts.synchronous
      )
  )

  val dataSource: ZLayer[Any, Throwable, DataSource] = ZLayer.scoped(
    ZIO.fromAutoCloseable(ZIO.attempt(EmbeddedPostgres.start())).map(_.getPostgresDatabase)
  )

  val tracedXa: ZLayer[DataSource & ZTracer, Throwable, doobie.Transactor[Task]] =
    xa >>> TracedTransactor.layer
  def spec = suite("Traced Transactor")(
    test("Captures queries") {
      val q = sql"SELECT 1".query[Int].unique
      for {
        _     <- ZIO.serviceWithZIO[Transactor[Task]](xa => q.transact(xa))
        spans <- ZIO.serviceWithZIO[InMemorySpanCompleter](_.retrieveCollected)
      } yield assertTrue(
        spans.exists(span => span.name == "SELECT 1")
      )
    },
    test("Captures failures") {
      val q = sql"this is not sql".query[Int].unique
      for {
        _     <- ZIO.serviceWithZIO[Transactor[Task]](xa => q.transact(xa)).exit
        spans <- ZIO.serviceWithZIO[InMemorySpanCompleter](_.retrieveCollected)
      } yield assertTrue(
        spans.exists(span =>
          span.name == "this is not sql" &&
            !span.status.isOk
        )
      )
    },
    test("Captures query parameters") {
      val p1 = 42
      val p2 = 24
      val q  = sql"SELECT $p1, $p2".query[(Int, Int)].unique
      for {
        _     <- ZIO.serviceWithZIO[Transactor[Task]](xa => q.transact(xa))
        spans <- ZIO.serviceWithZIO[InMemorySpanCompleter](_.retrieveCollected)
      } yield assertTrue(
        spans.exists(span =>
          span.name == "SELECT ?, ?" &&
            span.attributes.exists { case (k, v) => k == "?1" && v.value.value == p1.toString } &&
            span.attributes.exists { case (k, v) => k == "?2" && v.value.value == p2.toString }
        )
      )
    }
  ).provideSome[DataSource](
    tracedXa,
    InMemorySpanCompleter.layer("Traced XA")
  ).provideShared(
    dataSource
  )

}
