package io.kaizensolutions.trace4cats.zio.extras.tapir.examples

import com.comcast.ip4s.{Host, Port}
import fs2.io.net.Network
import io.circe.Codec as CirceCodec
import io.circe.generic.semiauto.deriveCodec
import io.kaizensolutions.trace4cats.zio.extras.ZTracer
import io.kaizensolutions.trace4cats.zio.extras.tapir.TapirServerTracer
import org.http4s.ember.server.EmberServerBuilder
import sttp.model.{Headers, StatusCode}
import sttp.tapir.*
import sttp.tapir.json.circe.*
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.http4s.Http4sServerInterpreter
import zio.*
import zio.interop.catz.*

import java.nio.charset.Charset

object ExampleServerApp extends ZIOAppDefault {
  implicit val fs2NetworkForTask: Network[Task] = Network.forAsync[Task]
  def countCharacters(tracer: ZTracer)(in: Request): UIO[Either[NoCharacters, Int]] = {
    val l = in.input.length
    val out = tracer.spanSource() {
      if (l > 0) ZIO.succeed(l)
      else ZIO.fail(NoCharacters("Please supply at least 1 character to count"))
    }

    out.either
  }

  val countCharactersEndpoint: Endpoint[Unit, Request, NoCharacters, Int, Any] =
    endpoint.post
      .in("count" / "characters")
      .in(stringBody(Charset.defaultCharset()))
      .in(headers)
      .mapIn(raw => Request(raw._1, Headers(raw._2)))(r => (r.input, r.headers.headers.toList))
      .errorOut(statusCode(StatusCode.BadRequest).and(jsonBody[NoCharacters]))
      .out(plainBody[Int])

  def serverEndpoint(tracer: ZTracer): ServerEndpoint.Full[Unit, Unit, Request, NoCharacters, Int, Any, Task] =
    countCharactersEndpoint.serverLogic(countCharacters(tracer))

  def tracedServerEndpoint(tracer: ZTracer): ServerEndpoint.Full[Unit, Unit, Request, NoCharacters, Int, Any, Task] =
    TapirServerTracer
      .traceEndpoint[Request, NoCharacters, Int, Any, Any, Throwable](
        tracer = tracer,
        serverEndpoint = serverEndpoint(tracer),
        extractRequestHeaders = _.headers,
        extractResponseHeaders = _ => Headers(Nil)
      )

  override val run: ZIO[ZIOAppArgs & Scope, Any, Any] = {
    val program =
      for {
        tracer  <- ZIO.service[ZTracer]
        endpoint = tracedServerEndpoint(tracer)
        httpApp  = Http4sServerInterpreter[Task]().toRoutes(endpoint).orNotFound
        port    <- ZIO.fromEither(Port.fromInt(8080).toRight(new RuntimeException("Invalid Port")))
        server <- EmberServerBuilder
                    .default[Task]
                    .withHostOption(Host.fromString("localhost"))
                    .withPort(port)
                    .withHttpApp(httpApp)
                    .build
                    .toScopedZIO <* ZIO.never
      } yield server

    program.provide(
      ZLayer.fromZIO(Scope.make),
      JaegarEntrypoint.live,
      ZTracer.layer
    )
  }
}

final case class NoCharacters(message: String)
object NoCharacters {
  implicit val codecForNoCharacters: CirceCodec[NoCharacters] =
    deriveCodec[NoCharacters]

  implicit val tapirSchemaForNoCharacters: Schema[NoCharacters] =
    Schema.derived[NoCharacters]
}
final case class Request(input: String, headers: Headers)
