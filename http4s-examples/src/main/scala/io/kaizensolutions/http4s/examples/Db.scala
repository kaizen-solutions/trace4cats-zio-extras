package io.kaizensolutions.http4s.examples

import zio.*

trait Db {
  def get(id: Int): Task[String]
}

object Db {
  def get(id: Int): RIO[Db, String] = ZIO.serviceWithZIO[Db](_.get(id))

  def live: ULayer[Db] = ZLayer.succeed(
    new Db {
      override def get(id: Int): Task[String] = ZIO.attempt(s"get $id")
    }
  )
}
