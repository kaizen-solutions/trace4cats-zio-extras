package io.kaizensolutions.trace4cats.zio.extras

import cats.effect.kernel.Resource
import io.janstenpickle.trace4cats.inject.EntryPoint
import zio._
import zio.blocking.Blocking
import zio.clock.Clock
import zio.interop.catz._

/**
 * Syntax to easily convert Trace4Cats Entrypoints into ZManaged ZEntrypoints.
 */
trait EntryPointResourceSyntax {
  implicit class TaskEntryPointOps(entryPoint: Resource[Task, EntryPoint[Task]]) {
    def toZManaged: TaskManaged[ZEntryPoint] =
      entryPoint.toManagedZIO
        .map(new ZEntryPoint(_))
  }

  implicit class RIOCBEntryPointOps(entryPoint: Resource[RIO[Clock & Blocking, *], EntryPoint[Task]]) {
    def toZManaged: RManaged[Clock & Blocking, ZEntryPoint] =
      entryPoint.toManagedZIO
        .map(new ZEntryPoint(_))
  }
}
