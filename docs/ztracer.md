---
sidebar_position: 3
title: ZTracer
---

# `ZTracer`

ZTracer is the abstraction provided by this library which allows you to seamlessly create traces & spans. For example:
```scala mdoc:compile-only
import zio.*
import io.kaizensolutions.trace4cats.zio.extras.ZTracer

val nestedTraceExample: URIO[ZTracer, Unit] =
  ZTracer.span("parent") {
    ZTracer
      .span("child1")(ZTracer.span("grandchild")(ZIO.unit))
      .zipParLeft(ZTracer.span("child2")(ZIO.unit))
  }
```

This generates the following structure:

[![Span structure](https://mermaid.ink/img/pako:eNptUDFuhDAQ_AramkO2wRi7SHXtVUkVuVlhE5DARo7R5YL4e_a4NlPN7s7OSLNDH50HA8Mc7_2IKRcfVxsKworJh1xcLm9FP06z4yf9ShjcOf-vElDC4tOCkyPX_SmykEe_eAuGqPMDbnO2YMNBUtxyfH-EHkxOmy9hWx1mf52QchYwA87ftF0xgNnhB0xdi4rzTouulZJJreoSHmA4Z1UjuWZaCNUIwlHCb4xkwSrVdEo3LZei7RR7fpDf53l8hXo35ZhuryLOPo4_i-FUTw?type=png)](https://mermaid.live/edit#pako:eNptUDFuhDAQ_AramkO2wRi7SHXtVUkVuVlhE5DARo7R5YL4e_a4NlPN7s7OSLNDH50HA8Mc7_2IKRcfVxsKworJh1xcLm9FP06z4yf9ShjcOf-vElDC4tOCkyPX_SmykEe_eAuGqPMDbnO2YMNBUtxyfH-EHkxOmy9hWx1mf52QchYwA87ftF0xgNnhB0xdi4rzTouulZJJreoSHmA4Z1UjuWZaCNUIwlHCb4xkwSrVdEo3LZei7RR7fpDf53l8hXo35ZhuryLOPo4_i-FUTw)

Notice that using the ZTracer abstraction cannot fail; meaning if you mis-configure the tracing configuration, 
your application will continue to function but **traces will not be reported**.

## `ZStream` integration
ZTracer can also be used to trace `ZStream`s in tandem with the `Spanned` datatype where each element of the `ZStream` 
has an associated span (i.e. Kafka messages). For example:
```scala mdoc:compile-only
import zio.*
import zio.stream.*
import io.kaizensolutions.trace4cats.zio.extras.*
import trace4cats.*

// create a stream where each element is associated with a trace header
val stream: ZStream[ZTracer, Nothing, (Int, TraceHeaders)] = 
  ZStream.range(1, 100)
  .mapZIO(i => 
    ZTracer.withSpan(s"name-$i")(span => 
      ZIO.succeed((i, span.extractHeaders(ToHeaders.standard)))
    )
  )

// create spans for elements in the stream
val tracedOperationStream: ZStream[ZTracer, Throwable, Spanned[Int]] = 
  stream.traceEachElement(element => /* we name each span based on the element*/ s"in-begin-$element") { 
    case (_, headers) => headers // we extract the headers from the element
  }
  .mapThrough(_._1) // we only care about the element, not the headers - traceEachElement will automatically add the headers back
  .mapZIOTraced("Plus 1")(e => 
    ZTracer.span(s"plus 1 for $e")(Console.printLine(s"Adding ${e} + 1 = ${e + 1}") *> ZIO.succeed(e + 1))
  )
  .mapZIOParTraced("Plus 2")(8)(e => 
    ZTracer.span(s"plus 2 for $e")(
      Console.printLine(s"Adding ${e} + 2 = ${e + 2}").delay(500.millis) *> 
        ZIO.succeed(e + 2)
    )
  )
  .mapZIOParTraced("Plus 4")(3)(e => 
    ZTracer.span(s"plus 4 for $e")(
      ZTracer.spanSource()(Console.printLine(s"Adding ${e} + 4 = ${e + 4}").delay(1.second)) *> 
        ZIO.succeed(e + 2)
    )
  )
```

Running the stream above, we'd expect to see something along the lines of:
![Jaegar span](https://github.com/kaizen-solutions/trace4cats-zio-extras/assets/14280155/59ae4606-2728-4be2-b76c-367ecaff175c)

Have a look at the [example](https://github.com/kaizen-solutions/trace4cats-zio-extras/blob/main/core-examples/src/main/scala/io/kaizensolutions/trace4cats/zio/core/examples/ExampleApp.scala) if you want to learn more.
