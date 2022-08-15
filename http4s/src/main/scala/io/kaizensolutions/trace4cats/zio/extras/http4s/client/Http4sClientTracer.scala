package io.kaizensolutions.trace4cats.zio.extras.http4s.client

import cats.effect.{MonadCancelThrow, Resource}
import trace4cats.ToHeaders
import trace4cats.http4s.common.{
  Http4sHeaders,
  Http4sSpanNamer,
  Http4sStatusMapping,
  Request_,
  Response_
}
import trace4cats.model.*
import trace4cats.model.AttributeValue.{LongValue, StringValue}
import io.kaizensolutions.trace4cats.zio.extras.{ZSpan, ZTracer}
import org.http4s.client.{Client, UnexpectedStatus}
import org.http4s.{Headers, Response, Uri}
import zio.*
import zio.interop.catz.*

object Http4sClientTracer {
  def traceClient[R, E <: Throwable](
    tracer: ZTracer,
    client: Client[ZIO[R, E, *]],
    toHeaders: ToHeaders = ToHeaders.standard,
    spanNamer: Http4sSpanNamer = Http4sSpanNamer.methodWithPath
  ): Client[ZIO[R, E, *]] =
    Client[ZIO[R, E, *]] { request =>
      val nameOfRequest = spanNamer(request: Request_)
      val spanScoped: URIO[Scope, ZSpan] =
        tracer
          .spanScopedManual(
            name = nameOfRequest,
            kind = SpanKind.Client,
            errorHandler = { case UnexpectedStatus(status, _, _) =>
              Http4sStatusMapping.toSpanStatus(status)
            }
          )

      val responseScoped: ZIO[R & Scope, E, Response[ZIO[R, E, *]]] =
        spanScoped.flatMap { span =>
          val traceHeaders: TraceHeaders  = span.extractHeaders(toHeaders)
          val http4sTraceHeaders: Headers = Http4sHeaders.converter.to(traceHeaders)
          val requestWithHeaders          = request.transformHeaders(_ ++ http4sTraceHeaders)
          val spanSampled                 = span.context.traceFlags.sampled == SampleDecision.Include

          // NOTE: We must respect the sampled flag
          val enrichWithAttributes: ZIO[R, E, Unit] =
            if (spanSampled) span.putAll(toAttributes(request))
            else ZIO.unit

          enrichWithAttributes *>
            tracer
              .locally(span) {
                client
                  .run(requestWithHeaders)
                  .toScopedZIO
                  .tap { response =>
                    val spanStatus     = Http4sStatusMapping.toSpanStatus(response.status)
                    val respAttributes = toAttributes(response)
                    span.putAll(respAttributes) *> span.setStatus(spanStatus).as(response)
                  }
                  .tapError(e =>
                    if (spanSampled) span.put("error.message", AttributeValue.StringValue(e.getLocalizedMessage))
                    else ZIO.unit
                  )
                  .tapDefect(cause =>
                    if (cause.isDie && spanSampled)
                      span.put("error.cause", AttributeValue.StringValue(cause.prettyPrint))
                    else ZIO.unit
                  )
              }
        }
      Resource.scopedZIO[R, E, Response[ZIO[R, E, *]]](responseScoped)
    }(concurrentInstance[R, E].asInstanceOf[MonadCancelThrow[ZIO[R, E, *]]]) // workaround as E is fixed to Throwable

  private def toAttributes(req: Request_): Map[String, AttributeValue] =
    Map[String, AttributeValue](
      SemanticAttributeKeys.httpFlavor -> s"${req.httpVersion.major}.${req.httpVersion.minor}",
      SemanticAttributeKeys.httpMethod -> req.method.name,
      SemanticAttributeKeys.httpUrl    -> req.uri.toString
    ) ++ req.uri.host.map { host =>
      val key = host match {
        case _: Uri.Ipv4Address => SemanticAttributeKeys.remoteServiceIpv4
        case _: Uri.Ipv6Address => SemanticAttributeKeys.remoteServiceIpv6
        case _: Uri.RegName     => SemanticAttributeKeys.remoteServiceHostname
      }
      key -> StringValue(host.value)
    }.toMap ++ req.uri.port.map(port => SemanticAttributeKeys.remoteServicePort -> LongValue(port.toLong))

  def toAttributes(res: Response_): Map[String, AttributeValue] =
    Map[String, AttributeValue](SemanticAttributeKeys.httpStatusCode -> res.status.code)
}
