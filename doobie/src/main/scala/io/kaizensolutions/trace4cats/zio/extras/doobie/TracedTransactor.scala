package io.kaizensolutions.trace4cats.zio.extras.doobie

import cats.data.Kleisli
import doobie.*
import io.kaizensolutions.trace4cats.zio.extras.{ZSpan, ZTracer}
import trace4cats.model.AttributeValue
import zio.*
import zio.interop.catz.*

import java.sql.{Connection, PreparedStatement, ResultSet}

/**
 * A transactor that will trace all database operations performed by the
 * underlying mechanism.
 *
 * This implementation was heavily inspired by the natchez-doobie integration
 * Special thanks to Eli Kasik (soujiro32167) for his help and ideas
 */
object TracedTransactor {
  private type Operation[A] = Kleisli[Task, PreparedStatement, A]

  val layer: URLayer[ZTracer & Transactor[Task], Transactor[Task]] =
    ZLayer.fromZIO(
      for {
        tracer <- ZIO.service[ZTracer]
        xa     <- ZIO.service[Transactor[Task]]
      } yield apply(xa, tracer)
    )

  def apply(underlying: Transactor[Task], tracer: ZTracer): Transactor[Task] = {
    val tracedInterpreter: KleisliInterpreter[Task] = createKleisliInterpreter(tracer)
    underlying.copy(interpret0 = tracedInterpreter.ConnectionInterpreter)
  }

  private def createKleisliInterpreter(tracer: ZTracer): KleisliInterpreter[Task] =
    new KleisliInterpreter[Task] { self =>
      override implicit val asyncM: WeakAsync[Task] = WeakAsync.doobieWeakAsyncForAsync[Task]

      override lazy val PreparedStatementInterpreter: PreparedStatementInterpreter =
        createPreparedStatementInterpreter(self, tracer)

      override lazy val ConnectionInterpreter: ConnectionInterpreter =
        createConnectionInterpreter(self)
    }

  private def createPreparedStatementInterpreter(
    ki: KleisliInterpreter[Task],
    tracer: ZTracer
  ): ki.PreparedStatementInterpreter =
    new ki.PreparedStatementInterpreter {
      private def traceOperation[A](f: Operation[A]): Operation[A] =
        Kleisli {
          case t: TrackedPreparedStatement =>
            val sql = formatRawSQLQuery(t.queryString)
            tracer.withSpan(sql) { span =>
              enrichSpanWithAttributes(span, t) *> f(t.underlying)
            }

          case other =>
            f(other)
        }

      override val executeBatch: Operation[Array[Int]] =
        traceOperation(super.executeBatch)

      override val executeLargeBatch: Operation[Array[Long]] =
        traceOperation(super.executeLargeBatch)

      override val execute: Operation[Boolean] =
        traceOperation(super.execute)

      override val executeUpdate: Operation[Int] =
        traceOperation(super.executeUpdate)

      override val executeQuery: Operation[ResultSet] =
        traceOperation(super.executeQuery)

      override val executeLargeUpdate: Operation[Long] =
        traceOperation(super.executeLargeUpdate)
    }

  private def createConnectionInterpreter(ki: KleisliInterpreter[Task]): ki.ConnectionInterpreter =
    new ki.ConnectionInterpreter {
      override def prepareStatement(rawQueryString: String): Kleisli[Task, Connection, PreparedStatement] =
        super
          .prepareStatement(rawQueryString)
          .map(TrackedPreparedStatement.make(rawQueryString))
    }

  private def formatRawSQLQuery(q: String): String =
    q.replace("\n", " ")
      .replaceAll("\\s+", " ")
      .trim()

  private def enrichSpanWithAttributes(span: ZSpan, tps: TrackedPreparedStatement): UIO[Unit] =
    ZIO
      .when(span.isSampled) {
        val parameters = tps.extractMutations.map { case (k, v) =>
          s"?$k" -> AttributeValue.StringValue(v.toString)
        }.toMap

        span.putAll(parameters)
      }
      .ignoreLogged
}
