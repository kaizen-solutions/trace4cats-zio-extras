package io.kaizensolutions.http4s.examples

import zio.*

trait Db {
  def get(id: Int): Task[String]
}

object Db {
  def get(id: Int): RIO[Has[Db], String] = ZIO.serviceWith[Db](_.get(id))

  def live: ULayer[Has[Db]] = ZLayer.succeed((id: Int) => IO(s"get $id"))
}
