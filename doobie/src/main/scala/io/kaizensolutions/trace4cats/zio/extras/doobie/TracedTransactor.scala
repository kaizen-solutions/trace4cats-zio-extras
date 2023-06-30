package io.kaizensolutions.trace4cats.zio.extras.doobie

import cats.data.NonEmptyList
import doobie.*
import doobie.util.log
import io.kaizensolutions.trace4cats.zio.extras.ZTracer
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
        tracer <- ZIO.service[ZTracer]
        xa     <- ZIO.service[Transactor[Task]]
        logHander <- ZIO.service[LogHandler[Task]]
      } yield apply(xa, tracer, logHander)
    )

  val default: URLayer[Transactor[Task] & ZTracer, Transactor[Task]] =
    ZLayer.succeed(LogHandler.noop[Task]) >>> layer

  private def tracingLogHandler(tracer: ZTracer): LogHandler[Task] = (logEvent: log.LogEvent) => {

    def trace(execution: FiniteDuration, processing: Option[FiniteDuration], failure: Option[Throwable]) = {
      tracer.withSpan(logEvent.sql.linesIterator.map(_.trim).mkString) { span =>
        def attributes =
          NonEmptyList.fromList(logEvent.args.map(_.toString))
          .map(nel => "query.arguments" -> AttributeValue.StringList(nel))
          .toMap ++
          Map(
            "query.label" -> AttributeValue.StringValue(logEvent.label),
            "query.execMillis" -> AttributeValue.LongValue(execution.toMillis),
            "query.sql" -> AttributeValue.StringValue(logEvent.sql)
          ) ++
          processing.map(p => "query.processingMillis" -> AttributeValue.LongValue(p.toMillis))

        ZIO.when(span.isSampled)(
          span.putAll(attributes) *>
            ZIO.foreachDiscard(failure)(f => span.setStatus(SpanStatus.Internal(f.getMessage)))
        ).unit
      } *> ZIO.debug(logEvent)
    }

    logEvent match {
      case log.Success(_, _, _, exec, processing) => trace(exec, Some(processing), None)
      case log.ProcessingFailure(_, _, _, exec, processing, failure) => trace(exec, Some(processing), Some(failure))
      case log.ExecFailure(_, _, _, exec, failure) => trace(exec, None, Some(failure))
    }
  }

  implicit class LogHandlerOps(val self: LogHandler[Task]) extends AnyVal {
    def andThen(other: LogHandler[Task]): LogHandler[Task] = (logEvent: log.LogEvent) =>
      self.run(logEvent) *> other.run(logEvent)
  }

  def apply(underlying: Transactor[Task], tracer: ZTracer, logHandler: LogHandler[Task]): Transactor[Task] = {
    underlying.copy(interpret0 = KleisliInterpreter(logHandler andThen tracingLogHandler(tracer)).ConnectionInterpreter)
  }
}
