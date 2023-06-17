---
sidebar_position: 1
title: Introduction
---

# Introduction
Trace4Cats ZIO Extras provides [**distributed tracing**](https://www.datadoghq.com/knowledge-center/distributed-tracing) 
for ZIO and its ecosystem built on top of the excellent [trace4cats](https://github.com/trace4cats/trace4cats) library.

## Getting started

[Latest Release](https://search.maven.org/search?q=g:io.kaizen-solutions%20AND%20a:trace4cats-zio-extras-*)

[![Maven Central](https://maven-badges.herokuapp.com/maven-central/io.kaizen-solutions/trace4cats-zio-extras-core_2.13/badge.svg)](https://maven-badges.herokuapp.com/maven-central/io.kaizen-solutions/trace4cats-zio-extras-core_2.13)


```scala
libraryDependencies ++= {
  val org = "io.kaizen-solutions"
  val version = "@VERSION@"

  Seq(
    org %% "trace4cats-zio-extras-core"       % version,  // core 

    // streaming
    org %% "trace4cats-zio-extras-fs2"        % version,  // fs2

    // http
    org %% "trace4cats-zio-extras-http4s"     % version,  // http4s
    org %% "trace4cats-zio-extras-sttp"       % version,  // sttp
    org %% "trace4cats-zio-extras-tapir"      % version,  // tapir
    org %% "trace4cats-zio-extras-zio-http"   % version,  // zio-http

    // database
    org %% "trace4cats-zio-extras-virgil"     % version,  // virgil
    org %% "trace4cats-zio-extras-doobie"     % version,  // doobie
    org %% "trace4cats-zio-extras-skunk"      % version,  // skunk
    
    // messaging
    org %% "trace4cats-zio-extras-fs2-kafka"  % version,  // fs2-kafka
    org %% "trace4cats-zio-extras-zio-kafka"  % version   // zio-kafka
  )
}
```

This library is also available on [JitPack](https://jitpack.io/#kaizen-solutions/trace4cats-zio-extras):

[![JitPack](https://jitpack.io/v/kaizen-solutions/trace4cats-zio-extras.svg)](https://jitpack.io/#kaizen-solutions/trace4cats-zio-extras)

**NOTE:** that the coordinates on JitPack are different from the ones on Maven Central. 
Please click the JitPack link or badge above to see the correct coordinates if you choose to go the JitPack route.

## Summary
This library provides the `ZTracer` abstraction in order to create [traces & spans](https://www.honeycomb.io/blog/datasets-traces-spans). 
It also provides a variety of integrations with popular libraries such as:

__HTTP__
- [http4s](https://http4s.org/)
- [zio-http](https://zio.dev/zio-http/)
- [tapir](https://tapir.softwaremill.com/en/latest/)
- [sttp](https://sttp.softwaremill.com/en/latest/)

__Database__
- [doobie](https://tpolecat.github.io/doobie/)
- [skunk](https://typelevel.org/skunk/)
- [virgil](https://github.com/kaizen-solutions/virgil)

__Messaging__
- [fs2-kafka](https://fd4s.github.io/fs2-kafka/)
- [zio-kafka](https://zio.dev/zio-kafka/)
    
__Streams__
- [fs2](https://fs2.io/)
- [zio-streams](https://zio.dev/reference/stream/)

## How it works
This library leverages Trace4Cats without making use of the typeclasses inside trace4cats (eg. `Provide`, etc.) and 
instead leverages `FiberRef`s hiding behind the `ZTracer` abstraction to call the underlying Trace4Cats APIs in order to 
provide a better experience for the user.

## Compatibility
This library is built for ZIO 2.x and is cross-built for Scala 2.12.x, 2.13.x and 3.3.x LTS targeting the latest version
of Trace4Cats.
