package io.kaizensolutions.virgil.trace4cats.zio.extras

import com.datastax.oss.driver.api.core.metrics.Metrics
import trace4cats.model.TraceProcess
import io.kaizensolutions.trace4cats.zio.extras.InMemorySpanCompleter
import io.kaizensolutions.virgil.*
import io.kaizensolutions.virgil.configuration.PageState
import io.kaizensolutions.virgil.cql.CqlStringContext
import io.kaizensolutions.virgil.dsl.*
import io.kaizensolutions.virgil.internal.Proofs.=:!=
import zio.*
import zio.stream.*
import zio.test.*

import java.util.UUID

object TracedCQLExecutorSpec extends ZIOSpecDefault {
  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("Traced CQL Executor Specification")(
      test("traces streaming queries")(
        for {
          result   <- setup
          (cql, sc) = result
          _ <- cql
                 .execute(
                   SelectBuilder
                     .from("example_table")
                     .columns("id", "data")
                     .where("id" === 1)
                     .buildRow
                 )
                 .runDrain
          spans <- sc.retrieveCollected
        } yield assertTrue(spans.length == 1) && {
          val span = spans.head
          assertTrue(
            span.name == "SELECT id, data FROM example_table WHERE id = :id_relation",
            span.attributes("virgil.bind-markers.id_relation").value.value == "1",
            span.attributes("virgil.query-type").value.value == "query",
            span.attributes("virgil.elements-to-pull").value.value == "All"
          )
        }
      ) +
        test("traces batched mutations")(
          for {
            result   <- setup
            (cql, sc) = result
            id1      <- ZIO.succeed(UUID.randomUUID())
            pop1      = 1
            u1        = cql"UPDATE cycling.popular_count SET popularity = popularity + $pop1 WHERE id = $id1".mutation
            id2      <- ZIO.succeed(UUID.randomUUID())
            pop2      = 125
            u2        = cql"UPDATE cycling.popular_count SET popularity = popularity + $pop2 WHERE id = $id2".mutation
            id3      <- ZIO.succeed(UUID.randomUUID())
            pop3      = 64
            u3        = cql"UPDATE cycling.popular_count SET popularity = popularity - $pop3 WHERE id = $id3".mutation
            _        <- cql.executeMutation(u1 + u2 + u3)
            spans    <- sc.retrieveCollected
          } yield assertTrue(spans.length == 1) && {
            val span = spans.head
            assertTrue(
              span.name == "BATCH(UPDATE cycling.popular_count SET popularity = popularity + :param0 WHERE id = :param1;UPDATE cycling.popular_count SET popularity = popularity + :param0 WHERE id = :param1;UPDATE cycling.popular_count SET popularity = popularity - :param0 WHERE id = :param1;)",
              span.attributes("virgil.query-type").value.value == "batch-mutation",
              span
                .attributes("virgil.query.0")
                .value
                .value == "UPDATE cycling.popular_count SET popularity = popularity + :param0 WHERE id = :param1",
              span
                .attributes("virgil.query.1")
                .value
                .value == "UPDATE cycling.popular_count SET popularity = popularity + :param0 WHERE id = :param1",
              span
                .attributes("virgil.query.2")
                .value
                .value == "UPDATE cycling.popular_count SET popularity = popularity - :param0 WHERE id = :param1",
              span.attributes("virgil.bind-markers.0.param0").value.value == pop1.toString,
              span.attributes("virgil.bind-markers.0.param1").value.value == id1.toString,
              span.attributes("virgil.bind-markers.1.param0").value.value == pop2.toString,
              span.attributes("virgil.bind-markers.1.param1").value.value == id2.toString,
              span.attributes("virgil.bind-markers.2.param0").value.value == pop3.toString,
              span.attributes("virgil.bind-markers.2.param1").value.value == id3.toString
            )
          }
        ) +
        test("traces paged queries") {
          for {
            result   <- setup
            (cql, sc) = result
            _ <- cql
                   .executePage(
                     SelectBuilder
                       .from("example_table")
                       .columns("id", "data")
                       .where("id" === 1)
                       .buildRow,
                     None
                   )
            spans <- sc.retrieveCollected
          } yield assertTrue(spans.length == 1) && {
            val span = spans.head
            assertTrue(
              span.name == "page-begin: SELECT id, data FROM example_table WHERE id = :id_relation",
              span.attributes("virgil.bind-markers.id_relation").value.value == "1",
              span.attributes("virgil.query-type").value.value == "query"
            )
          }
        }
    )

  val testCqlExecutor: CQLExecutor =
    new CQLExecutor {
      override def execute[A](in: CQL[A])(implicit trace: Trace): Stream[Throwable, A] =
        in.cqlType match {
          case _: CQLType.Mutation =>
            ZStream.succeed(MutationResult.make(true).asInstanceOf[A])

          case CQLType.Batch(_, _) =>
            ZStream.succeed(MutationResult.make(true).asInstanceOf[A])

          case CQLType.Query(_, _, _) =>
            ZStream.empty
        }

      override def executeMutation(in: CQL[MutationResult])(implicit trace: Trace): Task[MutationResult] =
        ZIO.attempt(MutationResult.make(true))

      override def executePage[A](in: CQL[A], pageState: Option[PageState])(implicit
        ev: A =:!= MutationResult,
        trace: Trace
      ): Task[Paged[A]] = ZIO.attempt(Paged(Chunk.empty, None))

      override def metrics: UIO[Option[Metrics]] = ZIO.none
    }

  val setup: URIO[Scope, (TracedCQLExecutor, InMemorySpanCompleter)] =
    for {
      result  <- InMemorySpanCompleter.entryPoint(TraceProcess("virgil-streaming-query"))
      (sc, ep) = result
      tracer  <- InMemorySpanCompleter.toZTracer(ep)
      cql      = TracedCQLExecutor(testCqlExecutor, tracer, _ => false)
    } yield (cql, sc)

}
