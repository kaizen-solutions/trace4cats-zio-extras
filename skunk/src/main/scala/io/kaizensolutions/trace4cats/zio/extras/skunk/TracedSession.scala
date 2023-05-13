package io.kaizensolutions.trace4cats.zio.extras.skunk

import io.kaizensolutions.trace4cats.zio.extras.{ZSpan, ZTracer}
import cats.effect.Resource
import cats.effect.kernel.Resource.ExitCase
import fs2.*
import fs2.concurrent.Signal
import skunk.*
import skunk.data.*
import skunk.net.protocol.{Describe, Parse}
import skunk.util.*
import trace4cats.kernel.ToHeaders
import trace4cats.model.{AttributeValue, SpanStatus}
import zio.*
import zio.interop.catz.*
final class TracedSession(underlying: Session[Task], tracer: ZTracer) extends Session[Task] {
  override def parameters: Signal[Task, Map[String, String]] = {
    def enrich(attrs: Map[String, String])(span: ZSpan): UIO[Unit] =
      ZIO
        .when(span.isSampled)(span.putAll(attrs.map { case (k, v) => (k, AttributeValue.StringValue(v)) }))
        .unit

    val ul = underlying.parameters
    new Signal[Task, Map[String, String]] {
      override def discrete: Stream[Task, Map[String, String]] =
        ul.discrete
          .evalTapChunk(map => tracer.withSpan("skunk.parameters.discrete")(enrich(map)))

      override def continuous: Stream[Task, Map[String, String]] =
        ul.continuous
          .evalTapChunk(map => tracer.withSpan("skunk.parameters.continuous")(enrich(map)))

      override def get: Task[Map[String, String]] =
        tracer.span("skunk.parameters.get")(ul.get)
    }
  }

  override def parameter(key: String): Stream[Task, String] =
    underlying
      .parameter(key)
      .evalTapChunk(attr =>
        tracer.withSpan(s"skunk.parameter.${key}")(span =>
          ZIO.when(span.isSampled)(
            span.putAll(
              ("skunk.parameter.key", key),
              ("skunk.parameter.value", attr)
            )
          )
        )
      )

  override def transactionStatus: Signal[Task, TransactionStatus] = {
    val ul = underlying.transactionStatus
    new Signal[Task, TransactionStatus] {
      override def discrete: Stream[Task, TransactionStatus] =
        ul.discrete.evalTapChunk(status =>
          tracer.withSpan("skunk.transactionStatus.discrete")(_.put("status", status.toString))
        )

      override def continuous: Stream[Task, TransactionStatus] =
        ul.continuous.evalTapChunk(status =>
          tracer.withSpan("skunk.transactionStatus.continuous")(_.put("status", status.toString))
        )

      override def get: Task[TransactionStatus] =
        tracer.withSpan("skunk.transactionStatus.get")(span =>
          ul.get.tap(status => span.put("status", status.toString))
        )
    }
  }

  override def execute[A](query: Query[Void, A]): Task[List[A]] =
    tracer.span(query.sql)(underlying.execute(query))

  override def execute[A, B](query: Query[A, B])(args: A): Task[List[B]] =
    tracer.withSpan(query.sql)(span =>
      enrichSpan(query, args, span, "execute") *>
        underlying
          .execute(query)(args)
          .onError(e => span.setStatus(SpanStatus.Internal(e.squash.getMessage)))
    )

  override def unique[A](query: Query[Void, A]): Task[A] =
    tracer.span(query.sql)(underlying.unique(query))

  override def unique[A, B](query: Query[A, B])(args: A): Task[B] =
    tracer.withSpan(query.sql)(span =>
      enrichSpan(query, args, span, "unique") *>
        underlying
          .unique(query)(args)
          .onError(e => span.setStatus(SpanStatus.Internal(e.squash.getMessage)))
    )

  override def option[A](query: Query[Void, A]): Task[Option[A]] =
    tracer.span(query.sql)(underlying.option(query))

  override def option[A, B](query: Query[A, B])(args: A): Task[Option[B]] =
    tracer.withSpan(query.sql)(span =>
      enrichSpan(query, args, span, "option") *>
        underlying
          .option(query)(args)
          .onError(e => span.setStatus(SpanStatus.Internal(e.squash.getMessage)))
    )

  override def stream[A, B](command: Query[A, B])(args: A, chunkSize: Int): fs2.Stream[Task, B] =
    Stream.force(
      tracer.withSpan(command.sql)(span =>
        enrichSpan(command, args, span, "stream") *>
          ZIO.succeed(
            underlying
              .stream(command)(args, chunkSize)
              .onFinalizeCaseWeak {
                case ExitCase.Succeeded  => ZIO.unit
                case ExitCase.Errored(e) => span.setStatus(SpanStatus.Internal(e.getMessage))
                case ExitCase.Canceled   => span.setStatus(SpanStatus.Cancelled)
              }
          )
      )
    )

  override def cursor[A, B](query: Query[A, B])(args: A): Resource[Task, Cursor[Task, B]] =
    underlying
      .cursor(query)(args)
      .evalMap(underlyingCursor =>
        tracer
          .withSpan(query.sql)(span =>
            Ref.make(0).map { timesPulled =>
              new Cursor[Task, B] {
                // NOTE: we have no guarantees that the outer span remains opened so we use TraceHeaders to propagate the context
                private val headers = span.extractHeaders(ToHeaders.standard)

                private def cursorSpan(fn: ZSpan => Task[(List[B], Boolean)]): Task[(List[B], Boolean)] =
                  tracer.fromHeaders(headers, s"skunk.cursor.fetch")(fn)

                private val updateTimesPulled = timesPulled.updateAndGet(_ + 1)

                override def fetch(maxRows: Int): Task[(List[B], Boolean)] =
                  cursorSpan { span =>
                    updateTimesPulled.flatMap(nr => span.put("times.pulled", nr)) *>
                      underlyingCursor.fetch(maxRows)
                  }
              }
            }
          )
      )

  override def execute(command: Command[Void]): Task[Completion] =
    tracer.span(command.sql)(underlying.execute(command))

  override def execute[A](command: Command[A])(args: A): Task[Completion] =
    tracer.withSpan(command.sql)(span =>
      enrichSpan(command, args, span, "execute") *>
        underlying
          .execute(command)(args)
          .onError(e => span.setStatus(SpanStatus.Internal(e.squash.getMessage)))
    )

  // TODO: Trace PreparedQuery
  override def prepare[A, B](query: Query[A, B]): Task[PreparedQuery[Task, A, B]] =
    tracer.withSpan(query.sql)(span => span.put("prepare.query", true) *> underlying.prepare(query))

  // TODO: Trace PreparedCommand
  override def prepare[A](command: Command[A]): Task[PreparedCommand[Task, A]] =
    tracer.withSpan(command.sql)(span => span.put("prepare.command", true) *> underlying.prepare(command))

  override def pipe[A](command: Command[A]): Pipe[Task, A, Completion] = {
    val pipe = underlying.pipe(command)

    streamA =>
      Stream.force(
        tracer.withSpan(command.sql)(span =>
          ZIO.succeed(
            streamA.through(pipe).onFinalizeCaseWeak {
              case ExitCase.Succeeded  => ZIO.unit
              case ExitCase.Errored(e) => span.setStatus(SpanStatus.Internal(e.getMessage))
              case ExitCase.Canceled   => span.setStatus(SpanStatus.Cancelled)
            }
          )
        )
      )
  }

  override def pipe[A, B](query: Query[A, B], chunkSize: Int): Pipe[Task, A, B] = {
    val pipe = underlying.pipe(query, chunkSize)

    streamA =>
      Stream.force(
        tracer.withSpan(query.sql)(span =>
          ZIO.succeed(
            streamA
              .evalTapChunk(a => tracer.withSpan("chunk")(span => enrichSpan(query, a, span, "pipe")))
              .through(pipe)
              .onFinalizeCaseWeak {
                case ExitCase.Succeeded  => ZIO.unit
                case ExitCase.Errored(e) => span.setStatus(SpanStatus.Internal(e.getMessage))
                case ExitCase.Canceled   => span.setStatus(SpanStatus.Cancelled)
              }
          )
        )
      )
  }

  override def channel(name: Identifier): Channel[Task, String, String] =
    new Channel[Task, String, String] {
      private val channelName = name.value
      private val channel     = underlying.channel(name)

      override def listen(maxQueued: Int): Stream[Task, Notification[String]] =
        channel
          .listen(maxQueued)
          .evalTapChunk(notification =>
            tracer.withSpan(s"channel.listen.$channelName")(span =>
              ZIO.when(span.isSampled)(
                span.putAll(
                  ("notification.pid", notification.pid),
                  ("notification.value", notification.value)
                )
              )
            )
          )

      override def listenR(maxQueued: Int): Resource[Task, Stream[Task, Notification[String]]] =
        channel
          .listenR(maxQueued)
          .evalMap(stream =>
            ZIO.succeed(
              stream
                .evalTapChunk(notification =>
                  tracer.withSpan(s"channel.listenR.$channelName")(span =>
                    ZIO.when(span.isSampled)(
                      span.putAll(
                        ("notification.pid", notification.pid),
                        ("notification.value", notification.value)
                      )
                    )
                  )
                )
            )
          )

      override def notify(message: String): Task[Unit] =
        tracer.span(s"channel.notify.$channelName")(channel.notify(message))
    }

  override def transaction[A]: Resource[Task, Transaction[Task]] =
    underlying.transaction
      .evalMap(tx => tracer.span("transaction")(ZIO.succeed(tracedTransaction(tx))))

  override def transaction[A](
    isolationLevel: TransactionIsolationLevel,
    accessMode: TransactionAccessMode
  ): Resource[Task, Transaction[Task]] =
    underlying
      .transaction(isolationLevel, accessMode)
      .evalMap(tx =>
        tracer.withSpan("transaction")(span =>
          span
            .putAll(
              ("isolation.level", isolationLevel.toString),
              ("access.mode", accessMode.toString)
            )
            .as(tracedTransaction(tx))
        )
      )

  override def typer: Typer = underlying.typer

  // NOTE: we can trace this as well
  override def describeCache: Describe.Cache[Task] =
    underlying.describeCache

  // NOTE: we can trace this as well
  override def parseCache: Parse.Cache[Task] =
    underlying.parseCache

  private def tracedTransaction(tx: Transaction[Task]): Transaction[Task] =
    new Transaction[Task] {
      override type Savepoint = tx.Savepoint

      override def status: Task[TransactionStatus] =
        tracer.span("status")(tx.status)

      override def savepoint(implicit o: Origin): Task[Savepoint] =
        tracer.withSpan("savepoint")(span => span.put("source.location", o.toString()) *> tx.savepoint)

      override def rollback(savepoint: Savepoint)(implicit o: Origin): Task[Completion] =
        tracer.withSpan("rollback")(span =>
          span.putAll(
            ("source.location", o.toString()),
            ("savepoint", savepoint.toString)
          ) *> tx.rollback(savepoint)
        )

      override def rollback(implicit o: Origin): Task[Completion] =
        tracer.withSpan("rollback")(span => span.put("source.location", o.toString()) *> tx.rollback)

      override def commit(implicit o: Origin): Task[Completion] = tx.commit
    }

  private def enrichSpan[A](statement: Statement[A], value: A, span: ZSpan, methodName: String): UIO[Unit] =
    ZIO
      .when(span.isSampled) {
        val values = statement.encoder.encode(value)

        // WARNING: mutable (builder) state is used here as this is on the hot path
        var index   = 1
        val builder = Map.newBuilder[String, AttributeValue]
        builder.sizeHint(values.size)

        values.foreach { value =>
          val attr = AttributeValue.StringValue(value.getOrElse("<null>"))
          val _    = builder += ((s"$$$index", attr))
          index += 1
        }

        builder += (("skunk.method", AttributeValue.StringValue(methodName)))

        span.putAll(builder.result())
      }
      .ignoreLogged
}
object TracedSession {
  def make(
    underlying: Session[Task],
    tracer: ZTracer
  ): Session[Task] =
    new TracedSession(underlying, tracer)

  def make(underlying: Session[Task]): URIO[ZTracer, TracedSession] =
    ZIO.serviceWith[ZTracer](new TracedSession(underlying, _))
}
