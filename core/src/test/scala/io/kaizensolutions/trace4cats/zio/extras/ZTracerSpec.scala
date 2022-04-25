package io.kaizensolutions.trace4cats.zio.extras

import io.janstenpickle.trace4cats.model.TraceProcess
import zio.test.environment.TestEnvironment
import zio.test.{assertTrue, DefaultRunnableSpec, ZSpec}
import zio.{Has, UIO, URIO, ZIO}

object ZTracerSpec extends DefaultRunnableSpec {
  override def spec: ZSpec[TestEnvironment, Any] =
    suite("ZTracer specification") {
      testM("nested traces are captured") {
        val nestedTrace: URIO[Has[ZTracer], Unit] = {
          
           // format: off
           //      parent
           //      /    \
           //    child1  child2
           //    /
           // grandchild
           // format: on
          ZTracer.span("parent") {
            ZTracer
              .span("child1")(ZTracer.span("grandchild")(UIO.unit))
              .zipParLeft(ZTracer.span("child2")(UIO.unit))
          }
        }

        for {
          result  <- InMemorySpanCompleter.entryPoint(TraceProcess("nested-trace-test"))
          (sc, ep) = result
          tracer  <- InMemorySpanCompleter.toZTracer(ep)
          _       <- nestedTrace.provide(Has(tracer))
          spans   <- sc.retrieveCollected
          gc      <- ZIO.fromOption(spans.find(_.name == "grandchild"))
          c1      <- ZIO.fromOption(spans.find(_.name == "child1"))
          c2      <- ZIO.fromOption(spans.find(_.name == "child2"))
          p       <- ZIO.fromOption(spans.find(_.name == "parent"))
        } yield assertTrue(spans.length == 4) &&
          // GC, C1, C2, P are unique spans
          assertTrue(Set(gc, c1, c2, p).size == 4) &&
          assertTrue(gc.name == "grandchild") &&
          assertTrue(c1.name == "child1") &&
          assertTrue(c2.name == "child2") &&
          assertTrue(p.name == "parent") &&
          // all spans belonging to the same trace share the trace id
          assertTrue(gc.context.traceId == c1.context.traceId) &&
          assertTrue(c1.context.traceId == c2.context.traceId) &&
          assertTrue(c2.context.traceId == p.context.traceId) &&
          // C1 and C2 have the same parent
          assertTrue(c1.context.parent == c2.context.parent) &&
          assertTrue(c1.context.parent.get.spanId == p.context.spanId) &&
          // GC is a child of C1
          assertTrue(gc.context.parent.map(_.spanId).get == c1.context.spanId)
      } +
        testM("spans belonging to different traces are isolated") {
          val trace1: URIO[Has[ZTracer], Unit] =
            ZTracer.span("a")(ZTracer.span("b")(UIO.unit))

          val trace2: URIO[Has[ZTracer], Unit] =
            ZTracer.span("x")(ZTracer.span("y")(ZTracer.span("z")(UIO.unit)))

          for {
            result        <- InMemorySpanCompleter.entryPoint(TraceProcess("nested-trace-test"))
            (sc, ep)       = result
            tracer        <- InMemorySpanCompleter.toZTracer(ep)
            _             <- trace1.zipPar(trace2).provide(Has(tracer))
            spans         <- sc.retrieveCollected
            uniqueTraceIds = spans.map(_.context.traceId).toSet
          } yield assertTrue(uniqueTraceIds.size == 2)
        }
    }
}
