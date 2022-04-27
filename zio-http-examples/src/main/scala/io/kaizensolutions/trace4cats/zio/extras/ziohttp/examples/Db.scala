package io.kaizensolutions.trace4cats.zio.extras.ziohttp.examples

import zio.{Has, IO, RIO, Task, ULayer, ZIO, ZLayer}

trait Db {
  def get(id: Int): Task[String]
}

object Db {
  def get(id: Int): RIO[Has[Db], String] = ZIO.serviceWith[Db](_.get(id))

  def live: ULayer[Has[Db]] = ZLayer.succeed((id: Int) => IO(s"get $id"))
}
