package io.kaizensolutions.trace4cats.zio.extras

import io.janstenpickle.trace4cats.model.{SpanId, TraceId}
import zio.*

import java.util.concurrent.{ConcurrentHashMap, ConcurrentMap}
import ElementTracerMap.SpannedCallback

import scala.jdk.CollectionConverters.*

/**
 * ElementTracerMap is responsible for holding the outermost span open of each
 * element in a stream in order to prevent premature submissions of spans to the
 * completer. It uses two promises:
 *   - A promise to extract the span from its lifecycle
 *   - A promise that is used to signal that the span is ready to be closed and
 *     submitted
 *
 * @param openScope
 * @param spanCloseMap
 */
final case class ElementTracerMap(
  openScope: ZScope.Open[Exit[Any, Any]],
  spanCloseMap: ConcurrentMap[(SpanId, TraceId), Promise[Nothing, Unit]]
) {
  def traceElement[A](element: A, callback: SpannedCallback): UIO[Spanned[A]] =
    for {
      waitSpan  <- Promise.make[Nothing, ZSpan]
      closeSpan <- Promise.make[Nothing, Unit]
      _ <- callback { span =>
             val trackElement = ZIO.succeed(spanCloseMap.put((span.context.spanId, span.context.traceId), closeSpan))
             ZIO.uninterruptible(waitSpan.succeed(span) *> trackElement) *> closeSpan.await
           }.forkIn(openScope.scope)
      span         <- waitSpan.await
      removeElement = ZIO.succeed(spanCloseMap.remove((span.context.spanId, span.context.traceId)))
    } yield Spanned(
      span = span,
      closeSpan = closeSpan.succeed(()).zip(removeElement),
      value = element
    )

  def cleanup(exit: Exit[Nothing, Any], strategy: ExecutionStrategy = ExecutionStrategy.Sequential): UIO[Boolean] = {
    def completePromise(p: Promise[Nothing, Unit]): UIO[Boolean] = exit match {
      case Exit.Success(_)     => p.succeed(())
      case Exit.Failure(cause) => p.halt(cause)
    }

    strategy match {
      case ExecutionStrategy.Sequential =>
        ZIO.foreach_(spanCloseMap.values().asScala)(completePromise) *> openScope.close(exit)

      case ExecutionStrategy.Parallel =>
        ZIO.foreachPar_(spanCloseMap.values().asScala)(completePromise) *> openScope.close(exit)

      case ExecutionStrategy.ParallelN(n) =>
        ZIO.foreachParN_(n)(spanCloseMap.values().asScala)(completePromise) *> openScope.close(exit)
    }
  }
}
object ElementTracerMap {
  type SpannedCallback = (ZSpan => UIO[Any]) => UIO[Any]

  def make(openScope: ZScope.Open[Exit[Any, Any]], capacity: Int = 128): UIO[ElementTracerMap] =
    ZIO
      .succeed(new ConcurrentHashMap[(SpanId, TraceId), Promise[Nothing, Unit]](capacity))
      .map(ElementTracerMap(openScope, _))
}
