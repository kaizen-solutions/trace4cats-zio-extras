package io.kaizensolutions.virgil.trace4cats.zio.extras

import com.datastax.oss.driver.api.core.metrics.Metrics
import com.datastax.oss.driver.api.core.{CqlSession, CqlSessionBuilder}
import trace4cats.model.{AttributeValue, SampleDecision, SpanKind, SpanStatus}
import io.kaizensolutions.trace4cats.zio.extras.{ZSpan, ZTracer}
import io.kaizensolutions.virgil.codecs.DecoderException
import io.kaizensolutions.virgil.configuration.PageState
import io.kaizensolutions.virgil.internal.CqlStatementRenderer
import io.kaizensolutions.virgil.internal.Proofs.*
import io.kaizensolutions.virgil.*
import zio.*
import zio.stream.*

import scala.collection.mutable

/**
 * A Traced version of CQLExecutor that will trace and enrich Spans with
 * metadata about the query
 * @param tracer
 *   is the ZTracer to use for tracing
 * @param underlying
 *   is the underlying CQLExecutor
 * @param dropMarkerFromSpan
 *   if true, the marker will be dropped from the span (use this if you don't
 *   want sensitive data to be in the span)
 */
class TracedCQLExecutor(underlying: CQLExecutor, tracer: ZTracer, dropMarkerFromSpan: String => Boolean)
    extends CQLExecutor {
  override def execute[A](in: CQL[A])(implicit trace: Trace): Stream[Throwable, A] =
    ZStream.unwrap(tracer.withSpan(extractQueryString(in), SpanKind.Internal) { span =>
      val enrichSpanWithBindMarkers =
        if (span.isSampled) enrichSpan(in, span, dropMarkerFromSpan)
        else ZIO.unit
      val spannedStream =
        underlying
          .execute(in)
          .tapError(enrichSpanWithErrorInformation(span))
          .tapErrorCause(cause =>
            if (cause.isDie) span.setStatus(SpanStatus.Internal(cause.prettyPrint))
            else ZIO.unit
          )
      enrichSpanWithBindMarkers *> ZIO.succeed(spannedStream)
    })

  override def executeMutation(in: CQL[MutationResult])(implicit trace: Trace): Task[MutationResult] =
    tracer.withSpan(extractQueryString(in), SpanKind.Internal) { span =>
      val isSampled = span.context.traceFlags.sampled == SampleDecision.Include
      val enrichSpanWithBindMarkers =
        if (isSampled) enrichSpan(in, span, dropMarkerFromSpan)
        else ZIO.unit

      enrichSpanWithBindMarkers *>
        underlying
          .executeMutation(in)
          .tapError(enrichSpanWithErrorInformation(span))
          .tapDefect(enrichSpanWithDefectInformation(span))
    }

  override def executePage[A](in: CQL[A], pageState: Option[PageState])(implicit
    ev: A =:!= MutationResult,
    trace: Trace
  ): Task[Paged[A]] = {
    val query  = extractQueryString(in)
    val pageNr = pageState.map(_.toString()).getOrElse("begin")

    tracer.withSpan(s"page-$pageNr: $query", SpanKind.Internal) { span =>
      val isSampled = span.context.traceFlags.sampled == SampleDecision.Include
      val enrichSpanWithBindMarkers =
        if (isSampled) enrichSpan(in, span, dropMarkerFromSpan)
        else ZIO.unit

      enrichSpanWithBindMarkers *>
        span.put("virgil.query-paged", AttributeValue.BooleanValue(true)) *>
        underlying
          .executePage(in, pageState)
          .tapError(enrichSpanWithErrorInformation(span))
          .tapDefect(enrichSpanWithDefectInformation(span))
    }
  }

  private def enrichSpanWithDefectInformation[E](currentSpan: ZSpan)(cause: Cause[E]): UIO[Unit] = {
    val addInfo = currentSpan.context.traceFlags.sampled.toBoolean
    if (addInfo) currentSpan.setStatus(SpanStatus.Internal(cause.prettyPrint))
    else ZIO.unit
  }

  private def enrichSpanWithErrorInformation(currentSpan: ZSpan)(error: Throwable): UIO[Unit] = {
    val addInfo = currentSpan.context.traceFlags.sampled.toBoolean
    val enrich = error match {
      case e: DecoderException.PrimitiveReadFailure =>
        currentSpan.putAll(
          Map(
            "virgil.error.message" -> AttributeValue.StringValue(e.message),
            "virgil.error.cause"   -> AttributeValue.StringValue(e.cause.getMessage)
          )
        )

      case e: DecoderException.StructureReadFailure =>
        currentSpan.putAll(
          Map(
            "virgil.error.message"             -> AttributeValue.StringValue(e.message),
            "virgil.error.cause"               -> AttributeValue.StringValue(e.cause.getMessage),
            "virgil.error.actual-db-structure" -> AttributeValue.StringValue(e.debugStructure),
            "virgil.error.field" -> AttributeValue
              .StringValue(e.field.map(_.toString).getOrElse("field-not-available"))
          )
        )

      case e =>
        val errorMessage =
          if (e != null && e.getMessage != null)
            Option("virgil.error.message" -> AttributeValue.StringValue(e.getMessage))
          else None

        val cause =
          if (e != null && e.getCause != null && e.getCause.getMessage != null)
            Option("virgil.error.cause" -> AttributeValue.StringValue(e.getCause.getMessage))
          else None

        val attrMap: Map[String, AttributeValue] = (errorMessage ++ cause).toMap

        if (attrMap.nonEmpty) currentSpan.putAll(attrMap)
        else ZIO.unit
    }

    if (addInfo) enrich
    else ZIO.unit
  }

  private def extractQueryString[A](in: CQL[A]) = {
    in.cqlType match {
      case mutation: CQLType.Mutation =>
        val (queryStr, _) = CqlStatementRenderer.render(mutation)
        queryStr

      case CQLType.Batch(mutations, _) =>
        val sb = new mutable.StringBuilder()
        sb.append("BATCH(")
        mutations.foreach { mut =>
          val (queryStr, _) = CqlStatementRenderer.render(mut)
          sb.append(queryStr)
          sb.append(";")
        }
        sb.append(")")
        sb.toString()

      case q @ CQLType.Query(_, _, _) =>
        val (queryStr, _) = CqlStatementRenderer.render(q)
        queryStr
    }
  }

  private def enrichSpan[A](in: CQL[A], span: ZSpan, dropMarkerFromSpan: String => Boolean) =
    in.cqlType match {
      case q @ CQLType.Query(_, _, pullMode) =>
        val attrMap      = mutable.Map.empty[String, AttributeValue]
        val (_, markers) = CqlStatementRenderer.render(q)

        attrMap += ("virgil.query-type"       -> AttributeValue.StringValue("query"))
        attrMap += ("virgil.elements-to-pull" -> AttributeValue.StringValue(pullMode.toString))
        markers.underlying.foreach { m =>
          val (name, marker) = m
          if (dropMarkerFromSpan(name.name)) ()
          else attrMap += (s"virgil.bind-markers.$name" -> AttributeValue.StringValue(marker.value.toString))
        }
        span.putAll(attrMap.toMap)

      case mutation: CQLType.Mutation =>
        val attrMap      = mutable.Map.empty[String, AttributeValue]
        val (_, markers) = CqlStatementRenderer.render(mutation)

        attrMap += ("virgil.query-type" -> AttributeValue.StringValue("mutation"))
        markers.underlying.foreach { m =>
          val (name, marker) = m
          if (dropMarkerFromSpan(name.name)) ()
          else attrMap += (s"virgil.bind-markers.$name" -> AttributeValue.StringValue(marker.value.toString))
        }
        span.putAll(attrMap.toMap)

      case CQLType.Batch(mutations, batchType: BatchType) =>
        var queryCounter = 0
        val attrMap      = mutable.Map.empty[String, AttributeValue]
        attrMap += ("virgil.batch-type" -> AttributeValue.StringValue(batchType.toString))
        attrMap += ("virgil.query-type" -> AttributeValue.StringValue("batch-mutation"))

        mutations.foreach { mut =>
          val (qs, markers) = CqlStatementRenderer.render(mut)
          attrMap += (s"virgil.query.$queryCounter" -> AttributeValue.StringValue(qs))
          markers.underlying.foreach { m =>
            val (name, marker) = m
            if (dropMarkerFromSpan(name.name)) ()
            else
              attrMap += (s"virgil.bind-markers.$queryCounter.$name" -> AttributeValue.StringValue(
                marker.value.toString
              ))
          }
          queryCounter += 1
        }
        span.putAll(attrMap.toMap)
    }

  override def metrics: UIO[Option[Metrics]] =
    tracer.span("metrics")(underlying.metrics)
}

object TracedCQLExecutor {
  def apply(
    underlying: CQLExecutor,
    tracer: ZTracer,
    dropMarkerFromSpan: String => Boolean
  ): TracedCQLExecutor = new TracedCQLExecutor(underlying, tracer, dropMarkerFromSpan)

  def apply(
    builder: CqlSessionBuilder,
    tracer: ZTracer,
    dropMarkerFromSpan: String => Boolean = _ => false
  ): RIO[Scope, CQLExecutor] =
    CQLExecutor(builder)
      .map(new TracedCQLExecutor(_, tracer, dropMarkerFromSpan))

  def fromCqlSession(
    session: CqlSession,
    tracer: ZTracer,
    dropMarkerFromSpan: String => Boolean = _ => false
  ): CQLExecutor =
    new TracedCQLExecutor(CQLExecutor.fromCqlSession(session), tracer, dropMarkerFromSpan)

  val layer: URLayer[CQLExecutor & ZTracer, CQLExecutor] =
    ZLayer.fromZIO(
      for {
        tracer <- ZIO.service[ZTracer]
        cql    <- ZIO.service[CQLExecutor]
      } yield new TracedCQLExecutor(cql, tracer, _ => false)
    )
}
