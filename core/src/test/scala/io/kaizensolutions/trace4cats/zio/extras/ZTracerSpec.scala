package io.kaizensolutions.trace4cats.zio.extras

import trace4cats.model.{AttributeValue, TraceProcess}
import zio.stream.ZStream
import zio.test.{assertTrue, Spec, TestEnvironment, ZIOSpecDefault}
import zio.{Chunk, Scope, URIO, ZEnvironment, ZIO}

object ZTracerSpec extends ZIOSpecDefault {
  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("ZTracer specification") {
      test("nested traces are captured") {
        val nestedTrace: URIO[ZTracer, Unit] = {
          
           // format: off
           //      parent
           //      /    \
           //    child1  child2
           //    /
           // grandchild
           // format: on
          ZTracer.span("parent") {
            ZTracer
              .span("child1")(ZTracer.span("grandchild")(ZIO.unit))
              .zipParLeft(ZTracer.span("child2")(ZIO.unit))
          }
        }

        for {
          result  <- InMemorySpanCompleter.entryPoint(TraceProcess("nested-trace-test"))
          (sc, ep) = result
          tracer  <- InMemorySpanCompleter.toZTracer(ep)
          _       <- nestedTrace.provideEnvironment(ZEnvironment(tracer))
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
        test("spans belonging to different traces are isolated") {
          val trace1: URIO[ZTracer, Unit] =
            ZTracer.span("a")(ZTracer.span("b")(ZIO.unit))

          val trace2: URIO[ZTracer, Unit] =
            ZTracer.span("x")(ZTracer.span("y")(ZTracer.span("z")(ZIO.unit)))

          for {
            result        <- InMemorySpanCompleter.entryPoint(TraceProcess("nested-trace-test"))
            (sc, ep)       = result
            tracer        <- InMemorySpanCompleter.toZTracer(ep)
            _             <- trace1.zipPar(trace2).provideEnvironment(ZEnvironment(tracer))
            spans         <- sc.retrieveCollected
            uniqueTraceIds = spans.map(_.context.traceId).toSet
          } yield assertTrue(uniqueTraceIds.size == 2)
        } +
        test("streaming spans are captured") {
          for {
            result  <- InMemorySpanCompleter.entryPoint(TraceProcess("streaming-trace-test"))
            (sc, ep) = result
            tracer  <- InMemorySpanCompleter.toZTracer(ep)
            executed <- (ZStream(1, 2, 3) @@ TraceAspects.traceEntireStream("streaming-trace")).runCollect
                          .provideEnvironment(ZEnvironment(tracer))
            spans <- sc.retrieveCollected
          } yield assertTrue(executed == Chunk(1, 2, 3), spans.length == 1)
        } +
        test("streaming spans are enriched") {
          for {
            result  <- InMemorySpanCompleter.entryPoint(TraceProcess("streaming-trace-test"))
            (sc, ep) = result
            tracer  <- InMemorySpanCompleter.toZTracer(ep)
            executed <-
              tracer
                .traceEntireStream("streaming-trace", enrich = span => span.put("stream-key", 1))(ZStream(1, 2, 3))
                .runCollect
                .provideEnvironment(ZEnvironment(tracer))
            spans <- sc.retrieveCollected
          } yield assertTrue(
            executed == Chunk(1, 2, 3),
            spans.length == 1,
            spans.head.attributes("stream-key") == AttributeValue.LongValue(1)
          )
        }
    }
}
