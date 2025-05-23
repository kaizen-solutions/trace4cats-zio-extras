package io.kaizensolutions.trace4cats.zio.extras.skunk.example

import cats.effect.kernel.Resource
import fs2.io.net.Network
import fs2.Stream
import io.kaizensolutions.trace4cats.zio.extras.ZTracer
import io.kaizensolutions.trace4cats.zio.extras.skunk.TracedSession
import io.kaizensolutions.trace4cats.zio.extras.skunk.example.Skunk.AccessSession
import org.typelevel.twiddles
import skunk.*
import skunk.codec.all.*
import skunk.syntax.all.*
import zio.*
import zio.interop.catz.*

object SkunkExample extends ZIOAppDefault {
  // Note: this is just done to illustrate tracing a cursor, just use `pq.stream` normally
  private def cursorBasedStream(pq: PreparedQuery[Task, Int *: Int *: EmptyTuple, City]): Stream[Task, City] =
    Stream
      .resource(pq.cursor(args = (1810, 1830)))
      .flatMap(cursor => Stream.repeatEval(cursor.fetch(1)).takeThrough { case (_, more) => more })
      .flatMap { case (result, _) => Stream.iterable(result) }

  override def run: ZIO[ZIOAppArgs & Scope, Any, Any] = {
    val program: ZIO[AccessSession, Throwable, Unit] =
      ZIO.scoped {
        for {
          as       <- ZIO.service[AccessSession]
          sessionA <- as.access
          sessionB <- as.access
          _         = println(sessionA)
          _         = println(sessionB)
          pq       <- sessionA.prepare(City.selectAllCities)
          _        <- pq.stream(args = (1810, 1830), chunkSize = 4).debug().compile.drain
          _        <- cursorBasedStream(pq).debug().compile.drain
        } yield ()
      }

    program.provide(
      OltpGrpcEntrypoint.live,
      ZTracer.layer,
      Skunk.layer
    )
  }
}

object Skunk {
  import cats.effect.std.Console
  import natchez.Trace.Implicits.noop

  implicit val consoleTask: Console[Task] = Console.make[Task]

  implicit val networkTask: Network[Task] = Network.forAsync[Task]

  final case class AccessSession(access: RIO[Scope, Session[Task]]) extends AnyVal

  // The outer resource is the pool, the inner resource represents taking a session from the pool
  def pooled(tracer: ZTracer): Resource[Task, Resource[Task, Session[Task]]] =
    Session
      .pooled[Task](
        host = "localhost",
        port = 5432,
        user = "postgres",
        password = Some("postgres"),
        database = "postgres",
        max = 8
      )
      .map(_.map(session => TracedSession.make(session, tracer)))

  // The ZIO version of the above
  val zioPooled: URIO[ZTracer & Scope, AccessSession] =
    for {
      tracer <- ZIO.service[ZTracer]
      // Note: Pool construction never fails because sessions in the pool are created lazily
      takeSessionFromPool <- pooled(tracer).toScopedZIO.orDie
    } yield AccessSession(takeSessionFromPool.toScopedZIO)

  val layer: URLayer[ZTracer, AccessSession] = ZLayer.scoped(zioPooled)
}

final case class City(id: Int, name: String, countryCode: String, district: String, population: Int)
object City {
  private val twiddleIsoCity = twiddles.Iso.product[City]

  private val underlying = int4 *: varchar *: bpchar(3) *: varchar *: int4

  val skunkDecoder: Decoder[City] = underlying.map(twiddleIsoCity.from)

  val skunkEncoder: Encoder[City] = underlying.contramap(twiddleIsoCity.to)

  // Alternatively, via the twiddles library (see Iso.productInstance)
  // val skunkCodec: Codec[City] = underlying.to[City]

  def selectAllCities: Query[Int *: Int *: EmptyTuple, City] =
    sql"SELECT id, name, countrycode, district, population from city  where id > $int4 and id < $int4"
      .query(City.skunkDecoder)
}
