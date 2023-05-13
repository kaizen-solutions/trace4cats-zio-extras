package io.kaizensolutions.trace4cats.zio.extras.skunk.example

import cats.effect.kernel.Resource
import fs2.io.net.Network
import io.kaizensolutions.trace4cats.zio.extras.ZTracer
import io.kaizensolutions.trace4cats.zio.extras.skunk.TracedSession
import io.kaizensolutions.trace4cats.zio.extras.skunk.example.Skunk.AccessSession
import skunk.*
import skunk.codec.all.*
import skunk.syntax.all.*
import zio.*
import zio.interop.catz.*

object SkunkExample extends ZIOAppDefault {
  override def run: ZIO[ZIOAppArgs & Scope, Any, Any] = {
    val program: ZIO[AccessSession, Throwable, Unit] =
      ZIO.scoped {
        for {
          as      <- ZIO.service[AccessSession]
          session <- as.access
          _       <- session.stream(City.selectAllCities)(args = (1810, 1830), chunkSize = 4).debug().compile.drain
        } yield ()
      }

    program.provide(
      JaegerEntrypoint.live,
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
  private val underlying = int4 *: varchar *: bpchar(3) *: varchar *: int4
  val skunkDecoder: Decoder[City] =
    underlying.map { case id *: name *: countryCode *: district *: population *: EmptyTuple =>
      City(id, name, countryCode, district, population)
    }

  val skunkEncoder: Encoder[City] =
    underlying.contramap { case City(id, name, countryCode, district, population) =>
      id *: name *: countryCode *: district *: population *: EmptyTuple
    }

  def selectAllCities: Query[(Int, Int), City] =
    sql"SELECT id, name, countrycode, district, population from city  where id > $int4 and id < $int4"
      .query(City.skunkDecoder)
}
