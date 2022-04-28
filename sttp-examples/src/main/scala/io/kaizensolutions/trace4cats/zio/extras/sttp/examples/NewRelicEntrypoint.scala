package io.kaizensolutions.trace4cats.zio.extras.sttp.examples

import io.janstenpickle.trace4cats.inject.EntryPoint
import io.janstenpickle.trace4cats.kernel.SpanSampler
import io.janstenpickle.trace4cats.model.TraceProcess
import io.janstenpickle.trace4cats.newrelic.{Endpoint, NewRelicSpanCompleter}
import io.kaizensolutions.trace4cats.zio.extras.*
import org.http4s.blaze.client.BlazeClientBuilder
import zio.blocking.Blocking
import zio.clock.Clock
import zio.interop.catz.*
import zio.{Has, RManaged, Task, URLayer, ZLayer, ZManaged}

object NewRelicEntrypoint {
  val live: URLayer[Clock & Blocking, Has[ZEntryPoint]] = ZLayer
    .fromManaged(
      entryPoint(TraceProcess("sttp-example-client-app"))
    )
    .orDie

  def entryPoint(process: TraceProcess): RManaged[Clock & Blocking, ZEntryPoint] =
    ZManaged.runtime[Clock & Blocking].flatMap { implicit rts =>
      (for {
        client <- BlazeClientBuilder[Task].resource
        completer <- NewRelicSpanCompleter[Task](
                       client = client,
                       process = process,
                       apiKey = "136bcc149f079eac2b2da7663aba7df6FFFFNRAL", // Insert your New Relic API key here
                       endpoint = Endpoint.US
                     )
      } yield EntryPoint[Task](SpanSampler.always[Task], completer)).toZManaged
    }
}
