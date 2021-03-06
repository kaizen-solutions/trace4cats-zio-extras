import sbtrelease.ReleaseStateTransformations._

inThisBuild {
  val scala212 = "2.12.16"
  val scala213 = "2.13.8"
  val scala313 = "3.1.3"

  Seq(
    scalaVersion                        := scala313,
    crossScalaVersions                  := Seq(scala212, scala213, scala313),
    githubWorkflowPublishTargetBranches := Seq.empty,
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    releaseTagName := s"${version.value}"
  )
}

ThisBuild / scalacOptions ++= {
  CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, 12 | 13)) => Seq("-Xsource:3")
    case Some((3, _))       => Seq.empty
    case Some(_) | None     => Seq.empty
  }
}

val isScala3 =
  Def.setting(CrossVersion.partialVersion(scalaVersion.value).exists { case (major, _) => major == 3 })

lazy val kindProjectorSettings = {
  Seq(
    libraryDependencies ++= {
      if (isScala3.value) Nil
      else Seq(compilerPlugin(("org.typelevel" %% "kind-projector" % "0.13.2").cross(CrossVersion.full)))
    }
  )
}

def releaseSettings: Seq[Def.Setting[_]] =
  Seq(
    versionScheme               := Some("early-semver"),
    releaseIgnoreUntrackedFiles := true,
    releaseTagName              := s"${version.value}",
    releaseProcess := Seq[ReleaseStep](
      checkSnapshotDependencies,
      inquireVersions,
      runClean,
      runTest,
      setReleaseVersion,
      commitReleaseVersion,
      tagRelease,
      setNextVersion,
      commitNextVersion,
      pushChanges
    ),
    publishTo := None,
    publish   := (())
  )

lazy val root =
  project
    .in(file("."))
    .settings(publish / skip := true)
    .aggregate(
      core,
      coreExample,
      fs2,
      fs2Example,
      fs2Kafka,
      fs2KafkaExample,
      http4s,
      http4sExample,
      zioHttp,
      zioHttpExample,
      sttp,
      sttpExample,
      tapir,
      tapirExample,
      virgil,
      virgilExample
    )

lazy val core = project
  .in(file("core"))
  .settings(kindProjectorSettings: _*)
  .settings(releaseSettings: _*)
  .settings(
    name             := "trace4cats-zio-extras-core",
    organization     := "io.kaizen-solutions",
    organizationName := "kaizen-solutions",
    libraryDependencies ++= {
      val liHaoyi    = "com.lihaoyi"
      val trace4cats = "io.janstenpickle"
      val typelevel  = "org.typelevel"
      val zio        = "dev.zio"

      Seq(
        liHaoyi    %% "sourcecode"        % Versions.sourceCode,
        trace4cats %% "trace4cats-core"   % Versions.trace4Cats,
        trace4cats %% "trace4cats-inject" % Versions.trace4Cats,
        typelevel  %% "cats-effect"       % Versions.catsEffect,
        zio        %% "zio"               % Versions.zio,
        zio        %% "zio-streams"       % Versions.zio,
        zio        %% "zio-interop-cats"  % Versions.zioInteropCats,
        zio        %% "zio-test"          % Versions.zio % Test,
        zio        %% "zio-test-sbt"      % Versions.zio % Test
      )
    }
  )

lazy val coreExample = project
  .in(file("core-examples"))
  .settings(kindProjectorSettings: _*)
  .settings(
    name             := "trace4cats-zio-extras-core-examples",
    organization     := "io.kaizen-solutions",
    organizationName := "kaizen-solutions",
    publish / skip   := true,
    libraryDependencies ++= {
      val trace4cats = "io.janstenpickle"
      Seq(trace4cats %% "trace4cats-jaeger-thrift-exporter" % Versions.trace4Cats)
    }
  )
  .dependsOn(core)

lazy val fs2 = project
  .in(file("fs2"))
  .settings(kindProjectorSettings: _*)
  .settings(releaseSettings: _*)
  .settings(
    name                            := "trace4cats-zio-extras-fs2",
    organization                    := "io.kaizen-solutions",
    libraryDependencies += "co.fs2" %% "fs2-core" % Versions.fs2
  )
  .dependsOn(core % "compile->compile;test->test")

lazy val fs2Example = project
  .in(file("fs2-examples"))
  .settings(kindProjectorSettings: _*)
  .settings(
    name             := "trace4cats-zio-extras-fs2-examples",
    organization     := "io.kaizen-solutions",
    organizationName := "kaizen-solutions",
    publish / skip   := true,
    libraryDependencies ++= {
      val trace4cats = "io.janstenpickle"
      Seq(trace4cats %% "trace4cats-jaeger-thrift-exporter" % Versions.trace4Cats)
    }
  )
  .dependsOn(fs2)

lazy val fs2Kafka =
  project
    .in(file("fs2-kafka"))
    .settings(kindProjectorSettings: _*)
    .settings(releaseSettings: _*)
    .settings(
      name                                     := "trace4cats-zio-extras-fs2-kafka",
      organization                             := "io.kaizen-solutions",
      libraryDependencies += "com.github.fd4s" %% "fs2-kafka" % Versions.fs2Kafka
    )
    .dependsOn(core % "compile->compile;test->test", fs2)

lazy val fs2KafkaExample =
  project
    .in(file("fs2-kafka-examples"))
    .settings(kindProjectorSettings: _*)
    .settings(
      name             := "trace4cats-zio-extras-fs2-kafka-examples",
      organization     := "io.kaizen-solutions",
      organizationName := "kaizen-solutions",
      publish / skip   := true,
      libraryDependencies ++= {
        val http4s     = "org.http4s"
        val trace4cats = "io.janstenpickle"

        Seq(
          http4s     %% "http4s-blaze-client"               % Versions.http4s,
          trace4cats %% "trace4cats-jaeger-thrift-exporter" % Versions.trace4Cats
        )
      }
    )
    .dependsOn(fs2Kafka)

lazy val http4s = project
  .in(file("http4s"))
  .settings(kindProjectorSettings: _*)
  .settings(releaseSettings: _*)
  .settings(
    name             := "trace4cats-zio-extras-http4s",
    organization     := "io.kaizen-solutions",
    organizationName := "kaizen-solutions",
    libraryDependencies ++= {
      val trace4Cats = "io.janstenpickle"
      val http4s     = "org.http4s"

      Seq(
        trace4Cats %% "trace4cats-http4s-common" % Versions.trace4Cats,
        http4s     %% "http4s-client"            % Versions.http4s
      )
    }
  )
  .dependsOn(core % "compile->compile;test->test")

lazy val http4sExample =
  project
    .in(file("http4s-examples"))
    .settings(kindProjectorSettings: _*)
    .settings(
      name             := "trace4cats-zio-extras-http4s-examples",
      organization     := "io.kaizen-solutions",
      organizationName := "kaizen-solutions",
      publish / skip   := true,
      libraryDependencies ++= {
        val http4s     = "org.http4s"
        val trace4cats = "io.janstenpickle"

        Seq(
          http4s     %% "http4s-blaze-server"               % Versions.http4s,
          http4s     %% "http4s-blaze-client"               % Versions.http4s,
          trace4cats %% "trace4cats-jaeger-thrift-exporter" % Versions.trace4Cats
        )
      }
    )
    .dependsOn(http4s)

lazy val zioHttp =
  project
    .in(file("zio-http"))
    .settings(kindProjectorSettings: _*)
    .settings(releaseSettings: _*)
    .settings(
      name                            := "trace4cats-zio-extras-zio-http",
      organization                    := "io.kaizen-solutions",
      organizationName                := "kaizen-solutions",
      libraryDependencies += "io.d11" %% "zhttp" % Versions.zhttp
    )
    .dependsOn(core)

lazy val zioHttpExample =
  project
    .in(file("zio-http-examples"))
    .settings(kindProjectorSettings: _*)
    .settings(
      name             := "trace4cats-zio-extras-zio-http-examples",
      organization     := "io.kaizen-solutions",
      organizationName := "kaizen-solutions",
      publish / skip   := true,
      libraryDependencies ++= {
        val trace4cats = "io.janstenpickle"
        Seq(trace4cats %% "trace4cats-jaeger-thrift-exporter" % Versions.trace4Cats)
      }
    )
    .dependsOn(zioHttp)

lazy val sttp =
  project
    .in(file("sttp"))
    .settings(kindProjectorSettings: _*)
    .settings(releaseSettings: _*)
    .settings(
      name                                                   := "trace4cats-zio-extras-sttp",
      organization                                           := "io.kaizen-solutions",
      organizationName                                       := "kaizen-solutions",
      libraryDependencies += "com.softwaremill.sttp.client3" %% "zio" % Versions.sttp,
      // Prevents org.scala-lang.modules:scala-collection-compat _3, _2.13 conflicting cross-version suffixes
      excludeDependencies += "org.scala-lang.modules" % "scala-collection-compat_2.13"
    )
    .dependsOn(core)

lazy val sttpExample =
  project
    .in(file("sttp-examples"))
    .settings(kindProjectorSettings: _*)
    .settings(
      name             := "trace4cats-zio-extras-zio-sttp-examples",
      organization     := "io.kaizen-solutions",
      organizationName := "kaizen-solutions",
      publish / skip   := true,
      libraryDependencies ++= Seq("io.janstenpickle" %% "trace4cats-jaeger-thrift-exporter" % Versions.trace4Cats),
      // Prevents org.scala-lang.modules:scala-collection-compat _3, _2.13 conflicting cross-version suffixes
      excludeDependencies += "org.scala-lang.modules" % "scala-collection-compat_2.13"
    )
    .dependsOn(sttp)

lazy val tapir =
  project
    .in(file("tapir"))
    .settings(kindProjectorSettings: _*)
    .settings(releaseSettings: _*)
    .settings(
      name                                                 := "trace4cats-zio-extras-tapir",
      organization                                         := "io.kaizen-solutions",
      organizationName                                     := "kaizen-solutions",
      libraryDependencies += "com.softwaremill.sttp.tapir" %% "tapir-core" % Versions.tapir
    )
    .dependsOn(core)

lazy val tapirExample =
  project
    .in(file("tapir-examples"))
    .settings(
      name             := "trace4cats-zio-extras-zio-sttp-examples",
      organization     := "io.kaizen-solutions",
      organizationName := "kaizen-solutions",
      publish / skip   := true,
      libraryDependencies ++=
        Seq(
          "io.janstenpickle"            %% "trace4cats-jaeger-thrift-exporter" % Versions.trace4Cats,
          "com.softwaremill.sttp.tapir" %% "tapir-json-circe"                  % Versions.tapir,
          "com.softwaremill.sttp.tapir" %% "tapir-http4s-server"               % Versions.tapir,
          "org.http4s"                  %% "http4s-blaze-server"               % Versions.http4s
        )
    )
    .dependsOn(tapir)

lazy val virgil =
  project
    .in(file("virgil"))
    .settings(kindProjectorSettings: _*)
    .settings(releaseSettings: _*)
    .settings(
      resolvers += "jitpack".at("https://jitpack.io"),
      name                                                        := "trace4cats-zio-extras-virgil",
      organization                                                := "io.kaizen-solutions",
      organizationName                                            := "kaizen-solutions",
      libraryDependencies += "com.github.kaizen-solutions.virgil" %% "virgil" % Versions.virgil
    )
    .dependsOn(core % "compile->compile;test->test")

lazy val virgilExample =
  project
    .in(file("virgil-examples"))
    .settings(kindProjectorSettings: _*)
    .settings(releaseSettings: _*)
    .settings(
      resolvers += "jitpack".at("https://jitpack.io"),
      name             := "trace4cats-zio-extras-virgil-examples",
      organization     := "io.kaizen-solutions",
      organizationName := "kaizen-solutions",
      libraryDependencies ++=
        Seq(
          "io.janstenpickle"                   %% "trace4cats-jaeger-thrift-exporter" % Versions.trace4Cats,
          "com.github.kaizen-solutions.virgil" %% "virgil"                            % Versions.virgil
        )
    )
    .dependsOn(core, virgil)
