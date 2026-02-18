package io.kaizensolutions.trace4cats.zio.extras.tapir

import io.kaizensolutions.trace4cats.zio.extras.ZTracer
import sttp.tapir.ztapir.ZServerEndpoint
import sttp.model.StatusCode
import sttp.tapir.DecodeResult
import zio.*
import sttp.tapir.ztapir.*

final case class TestEndpoint(tracer: ZTracer) {

  val testEndpoint: ZServerEndpoint[Any, Any] =
    endpoint.get
      .securityIn(header[Option[String]]("auth"))
      .errorOut(stringBody.and(statusCode(StatusCode.Unauthorized)))
      .zServerSecurityLogic{
        case None => ZIO.unit
        case Some(v) => ZIO.whenDiscard(v == "invalid")(ZIO.fail("invalid"))
      }
      .in("hello" / path[String]("name") / "greeting")
      .in(stringBody.mapDecode(s => if (s != "invalid") DecodeResult.Value(s) else DecodeResult.Error(s"$s was invalid", new Exception("nope")))(identity))
        .serverLogic { _ => { case (name, _) =>
          tracer.withSpan("moshi") { span =>
            span.put("hello", name).unit
          }
        }
      }
}
