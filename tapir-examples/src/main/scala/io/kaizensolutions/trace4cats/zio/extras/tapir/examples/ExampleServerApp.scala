package io.kaizensolutions.trace4cats.zio.extras.tapir.examples

import io.circe.Codec as CirceCodec
import io.circe.generic.semiauto.deriveCodec
import io.kaizensolutions.trace4cats.zio.extras.ZTracer
import io.kaizensolutions.trace4cats.zio.extras.tapir.TapirServerTracer
import org.http4s.blaze.server.BlazeServerBuilder
import sttp.model.{Headers, StatusCode}
import sttp.tapir.*
import sttp.tapir.json.circe.*
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.http4s.Http4sServerInterpreter
import zio.*
import zio.blocking.Blocking
import zio.clock.Clock
import zio.interop.catz.*

import java.nio.charset.Charset

object ExampleServerApp extends App {
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

  override def run(args: List[String]): URIO[ZEnv, ExitCode] = {
    val program =
      ZIO.runtime[Clock & Blocking].flatMap { implicit rts =>
        for {
          tracer  <- ZIO.service[ZTracer]
          endpoint = tracedServerEndpoint(tracer)
          httpApp  = Http4sServerInterpreter[Task]().toRoutes(endpoint).orNotFound
          server <- BlazeServerBuilder[Task]
                      .bindHttp(8080, "localhost")
                      .withHttpApp(httpApp)
                      .resource
                      .toManagedZIO
                      .useForever
        } yield server
      }

    program.exitCode
      .provideCustomLayer(JaegarEntrypoint.live >>> ZTracer.live)
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
