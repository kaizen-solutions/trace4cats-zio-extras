package io.kaizensolutions.trace4cats.zio.extras.doobie

import cats.data.NonEmptyList
import doobie.*
import doobie.util.log
import doobie.util.log.Parameters
import io.kaizensolutions.trace4cats.zio.extras.{OtelSemconv, ZTracer}
import trace4cats.model.{AttributeValue, SpanStatus}
import zio.*
import zio.interop.catz.*

import scala.concurrent.duration.FiniteDuration

/**
 * A transactor that will trace all database operations performed by the
 * underlying mechanism.
 *
 * This implementation was heavily inspired by the natchez-doobie integration
 * Special thanks to Eli Kasik (soujiro32167) for his help and ideas
 */
object TracedTransactor {
  val layer: URLayer[ZTracer & Transactor[Task] & LogHandler[Task], Transactor[Task]] =
    ZLayer.fromZIO(
      for {
        tracer    <- ZIO.service[ZTracer]
        xa        <- ZIO.service[Transactor[Task]]
        logHander <- ZIO.service[LogHandler[Task]]
        dbSystem  <- detectDbSystem(xa)
      } yield apply(xa, tracer, logHander, dbSystem)
    )

  val default: URLayer[Transactor[Task] & ZTracer, Transactor[Task]] =
    ZLayer.succeed(LogHandler.noop[Task]) >>> layer

  private def detectDbSystem(xa: Transactor[Task]): UIO[String] = {
    import doobie.implicits.*
    FC.raw(_.getMetaData.getDatabaseProductName)
      .transact(xa)
      .orElseSucceed("unknown")
  }

  private def tracingLogHandler(tracer: ZTracer, dbSystem: String): LogHandler[Task] = (logEvent: log.LogEvent) => {

    def trace(execution: FiniteDuration, processing: Option[FiniteDuration], failure: Option[Throwable]) = {
      tracer.withSpan(logEvent.sql.linesIterator.map(_.trim).mkString) { span =>
        def attributes = {
          val parameters = logEvent.params match {
            case Parameters.NonBatch(paramsAsList) => paramsAsList.map(_.toString)
            case Parameters.Batch(paramsAsLists)   => paramsAsLists().map(_.map(_.toString).mkString("[", ",", "]"))
          }
          NonEmptyList
            .fromList(parameters)
            .map(nel => "db.query.parameter.values" -> AttributeValue.StringList(nel))
            .toMap ++
            Map(
              OtelSemconv.DbSystemName    -> AttributeValue.StringValue(dbSystem),
              OtelSemconv.DbOperationName -> AttributeValue.StringValue(logEvent.label),
              OtelSemconv.DbQueryText     -> AttributeValue.StringValue(logEvent.sql),
              "db.query.exec_millis"      -> AttributeValue.LongValue(execution.toMillis)
            ) ++
            processing.map(p => "db.query.processing_millis" -> AttributeValue.LongValue(p.toMillis))
        }

        ZIO
          .when(span.isSampled)(
            span.putAll(attributes) *>
              ZIO.foreachDiscard(failure)(f =>
                span.setStatus(SpanStatus.Internal(f.getMessage)) *>
                  span.put(OtelSemconv.ErrorType, AttributeValue.StringValue(f.getClass.getCanonicalName))
              )
          )
          .unit
      }
    }

    logEvent match {
      case log.Success(_, _, _, exec, processing)                    => trace(exec, Some(processing), None)
      case log.ProcessingFailure(_, _, _, exec, processing, failure) => trace(exec, Some(processing), Some(failure))
      case log.ExecFailure(_, _, _, exec, failure)                   => trace(exec, None, Some(failure))
    }
  }

  implicit class LogHandlerOps(val self: LogHandler[Task]) extends AnyVal {
    def andThen(other: LogHandler[Task]): LogHandler[Task] = (logEvent: log.LogEvent) =>
      self.run(logEvent) *> other.run(logEvent)
  }

  def apply(
    underlying: Transactor[Task],
    tracer: ZTracer,
    logHandler: LogHandler[Task],
    dbSystem: String
  ): Transactor[Task] =
    underlying.copy(interpret0 =
      KleisliInterpreter(logHandler.andThen(tracingLogHandler(tracer, dbSystem))).ConnectionInterpreter
    )
}
