package io.kaizensolutions.virgil

import com.datastax.oss.driver.api.core.{CqlSession, CqlSessionBuilder}
import io.janstenpickle.trace4cats.model.{AttributeValue, SampleDecision, SpanKind}
import io.kaizensolutions.trace4cats.zio.extras.{ZSpan, ZTracer}
import io.kaizensolutions.virgil.configuration.PageState
import io.kaizensolutions.virgil.internal.CqlStatementRenderer
import io.kaizensolutions.virgil.internal.Proofs.*
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
  override def execute[A](in: CQL[A]): Stream[Throwable, A] = {
    val span = tracer.spanManaged(extractQueryString(in), SpanKind.Internal)
    ZStream
      .managed(span.tapM(tracer.updateCurrentSpan))
      .flatMap { span =>
        val isSampled = span.context.traceFlags.sampled == SampleDecision.Include
        val enrichSpanWithBindMarkers =
          if (isSampled) enrichSpan(in, span, dropMarkerFromSpan)
          else ZIO.unit

        ZStream.execute(enrichSpanWithBindMarkers).drain ++
          underlying
            .execute(in)
            .ensuring(tracer.removeCurrentSpan)
      }
  }

  override def executeMutation(in: CQL[MutationResult]): Task[MutationResult] =
    tracer.withSpan(extractQueryString(in), SpanKind.Internal) { span =>
      val isSampled = span.context.traceFlags.sampled == SampleDecision.Include
      val enrichSpanWithBindMarkers =
        if (isSampled) enrichSpan(in, span, dropMarkerFromSpan)
        else ZIO.unit

      enrichSpanWithBindMarkers *> underlying.executeMutation(in)
    }

  override def executePage[A](in: CQL[A], pageState: Option[PageState])(implicit
    ev: A =:!= MutationResult
  ): Task[Paged[A]] = {
    val query  = extractQueryString(in)
    val pageNr = pageState.map(_.toString()).getOrElse("begin")

    tracer.withSpan(s"$query-page-$pageNr", SpanKind.Internal) { span =>
      val isSampled = span.context.traceFlags.sampled == SampleDecision.Include
      val enrichSpanWithBindMarkers =
        if (isSampled) enrichSpan(in, span, dropMarkerFromSpan)
        else ZIO.unit

      enrichSpanWithBindMarkers *>
        span.put("virgil.query-paged", AttributeValue.BooleanValue(true)) *>
        underlying.executePage(in, pageState)
    }
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
        val attrMap       = mutable.Map.empty[String, AttributeValue]
        val (qs, markers) = CqlStatementRenderer.render(q)
        attrMap += ("virgil.query"            -> AttributeValue.StringValue(qs))
        attrMap += ("virgil.query-type"       -> AttributeValue.StringValue("query"))
        attrMap += ("virgil.elements-to-pull" -> AttributeValue.StringValue(pullMode.toString))
        markers.underlying.foreach { m =>
          val (name, marker) = m
          if (dropMarkerFromSpan(name.name)) ()
          else attrMap += (s"virgil.query.$name" -> AttributeValue.StringValue(marker.value.toString))
        }
        span.putAll(attrMap.toMap)

      case mutation: CQLType.Mutation =>
        val attrMap       = mutable.Map.empty[String, AttributeValue]
        val (qs, markers) = CqlStatementRenderer.render(mutation)
        attrMap += ("virgil.query"      -> AttributeValue.StringValue(qs))
        attrMap += ("virgil.query-type" -> AttributeValue.StringValue("mutation"))
        markers.underlying.foreach { m =>
          val (name, marker) = m
          if (dropMarkerFromSpan(name.name)) ()
          else attrMap += (s"virgil.query.$name" -> AttributeValue.StringValue(marker.value.toString))
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
            else attrMap += (s"virgil.query.$queryCounter.$name" -> AttributeValue.StringValue(marker.value.toString))
          }
          queryCounter += 1
        }
        span.putAll(attrMap.toMap)
    }
}

object TracedCQLExecutor {
  def apply(
    builder: CqlSessionBuilder,
    tracer: ZTracer,
    dropMarkerFromSpan: String => Boolean = _ => false
  ): TaskManaged[CQLExecutor] =
    CQLExecutor(builder)
      .map(new TracedCQLExecutor(_, tracer, dropMarkerFromSpan))

  def fromCqlSession(
    session: CqlSession,
    tracer: ZTracer,
    dropMarkerFromSpan: String => Boolean = _ => false
  ): CQLExecutor =
    new TracedCQLExecutor(CQLExecutor.fromCqlSession(session), tracer, dropMarkerFromSpan)

  val layer: URLayer[Has[CQLExecutor] & Has[ZTracer], Has[CQLExecutor]] =
    ZLayer.fromEffect(
      for {
        tracer <- ZIO.service[ZTracer]
        cql    <- ZIO.service[CQLExecutor]
      } yield new TracedCQLExecutor(cql, tracer, _ => false)
    )
}
