package io.kaizensolutions.trace4cats.zio.extras.ziohttp.examples

import zio.{RIO, Task, ULayer, ZIO, ZLayer}

trait Db {
  def get(id: Int): Task[String]
}

object Db {
  def get(id: Int): RIO[Db, String] = ZIO.serviceWithZIO[Db](_.get(id))

  def live: ULayer[Db] =
    ZLayer.succeed(
      new Db {
        override def get(id: Int): Task[String] =
          ZIO.attempt(s"get $id")
      }
    )
}
