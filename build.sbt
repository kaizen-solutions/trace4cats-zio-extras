import org.typelevel.scalacoptions.ScalacOptions

inThisBuild {
  val scala213 = "2.13.18"
  val scala3   = "3.3.7"

  Seq(
    scalaVersion       := scala213,
    crossScalaVersions := Seq(scala213, scala3),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    versionScheme := Some("early-semver"),
    githubWorkflowJavaVersions := List(
      JavaSpec.temurin("17"),
      JavaSpec.temurin("21")
    ),
    githubWorkflowTargetTags ++= Seq("v*"),
    githubWorkflowPublishTargetBranches := Seq(
      RefPredicate.StartsWith(Ref.Tag("v")),
      RefPredicate.Equals(Ref.Branch("main"))
    ),
    githubWorkflowPublish := Seq(
      WorkflowStep.Sbt(
        commands = List("ci-release"),
        name = Some("Publish project"),
        env = Map(
          "PGP_PASSPHRASE"    -> "${{ secrets.PGP_PASSPHRASE }}",
          "PGP_SECRET"        -> "${{ secrets.PGP_SECRET }}",
          "SONATYPE_PASSWORD" -> "${{ secrets.SONATYPE_PASSWORD }}",
          "SONATYPE_USERNAME" -> "${{ secrets.SONATYPE_USERNAME }}"
        )
      )
    ),
    developers := List(
      Developer("calvinlfer", "Calvin Fernandes", "cal@kaizen-solutions.io", url("https://www.kaizen-solutions.io")),
      Developer("soujiro32167", "Eli Kasik", "soujiro32167@gmail.com", url("https://trampolinelab.com"))
    ),
    licenses         := List("MIT" -> url("https://opensource.org/licenses/MIT")),
    organization     := "io.kaizen-solutions",
    organizationName := "kaizen-solutions",
    homepage         := Some(url("https://www.kaizen-solutions.io"))
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
      else Seq(compilerPlugin(("org.typelevel" %% "kind-projector" % "0.13.4").cross(CrossVersion.full)))
    }
  )
}

def mkModule(projectName: String) =
  Project(projectName, file(projectName))
    .settings(kindProjectorSettings *)
    .settings(name := s"trace4cats-zio-extras-$projectName")

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
      sttp4,
      sttp4Example,
      tapir,
      tapirExample,
      virgil,
      virgilExample,
      doobie,
      doobieExample,
      skunk,
      skunkExample,
      zioKafka,
      zioKafkaExamples,
      docs
    )

lazy val core = project
  .in(file("core"))
  .settings(kindProjectorSettings *)
  .settings(
    name             := "trace4cats-zio-extras-core",
    organization     := "io.kaizen-solutions",
    organizationName := "kaizen-solutions",
    libraryDependencies ++= {
      val liHaoyi    = "com.lihaoyi"
      val scribe     = "com.outr"
      val trace4cats = "io.janstenpickle"
      val typelevel  = "org.typelevel"
      val zio        = "dev.zio"

      Seq(
        liHaoyi    %% "sourcecode"       % Versions.sourceCode,
        trace4cats %% "trace4cats-core"  % Versions.trace4Cats,
        typelevel  %% "cats-effect"      % Versions.catsEffect,
        zio        %% "zio"              % Versions.zio,
        zio        %% "zio-streams"      % Versions.zio,
        zio        %% "zio-interop-cats" % Versions.zioInteropCats,
        zio        %% "zio-test"         % Versions.zio    % Test,
        zio        %% "zio-test-sbt"     % Versions.zio    % Test,
        scribe     %% "scribe-slf4j"     % Versions.scribe % Test
      )
    }
  )

lazy val coreExample = project
  .in(file("core-examples"))
  .settings(kindProjectorSettings *)
  .settings(
    name           := "trace4cats-zio-extras-core-examples",
    publish / skip := true,
    libraryDependencies ++= {
      val trace4cats = "io.janstenpickle"
      Seq(trace4cats %% "trace4cats-opentelemetry-otlp-grpc-exporter" % Versions.trace4CatsGRPCExporter)
    }
  )
  .dependsOn(core)

lazy val zquery = project
  .in(file("zquery"))
  .settings(kindProjectorSettings *)
  .settings(
    name             := "trace4cats-zio-extras-zquery",
    organization     := "io.kaizen-solutions",
    organizationName := "kaizen-solutions",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-query" % Versions.zquery
    )
  )
  .dependsOn(core % "compile->compile;test->test")

lazy val fs2 = project
  .in(file("fs2"))
  .settings(kindProjectorSettings *)
  .settings(
    name                            := "trace4cats-zio-extras-fs2",
    libraryDependencies += "co.fs2" %% "fs2-core" % Versions.fs2
  )
  .dependsOn(core % "compile->compile;test->test")

lazy val fs2Example = project
  .in(file("fs2-examples"))
  .settings(kindProjectorSettings *)
  .settings(
    name           := "trace4cats-zio-extras-fs2-examples",
    publish / skip := true,
    libraryDependencies ++= {
      val trace4cats = "io.janstenpickle"
      Seq(trace4cats %% "trace4cats-opentelemetry-otlp-grpc-exporter" % Versions.trace4CatsGRPCExporter)
    }
  )
  .dependsOn(fs2)

lazy val fs2Kafka =
  project
    .in(file("fs2-kafka"))
    .settings(kindProjectorSettings *)
    .settings(
      name := "trace4cats-zio-extras-fs2-kafka",
      libraryDependencies ++= Seq(
        "com.github.fd4s"         %% "fs2-kafka"         % Versions.fs2Kafka,
        "io.github.embeddedkafka" %% "embedded-kafka"    % Versions.kafkaEmbedded % Test,
        "dev.zio"                 %% "zio-logging-slf4j" % Versions.zioLogging    % Test,
        "ch.qos.logback"           % "logback-classic"   % Versions.logback       % Test
      ),
      // Prevents org.scala-lang.modules:scala-collection-compat _3, _2.13 conflicting cross-version suffixes
      excludeDependencies += "org.scala-lang.modules" % "scala-collection-compat_2.13"
    )
    .dependsOn(core % "compile->compile;test->test", fs2)

lazy val fs2KafkaExample =
  project
    .in(file("fs2-kafka-examples"))
    .settings(kindProjectorSettings *)
    .settings(
      name           := "trace4cats-zio-extras-fs2-kafka-examples",
      publish / skip := true,
      libraryDependencies ++= {
        val http4s     = "org.http4s"
        val trace4cats = "io.janstenpickle"

        Seq(
          http4s     %% "http4s-ember-client"                         % Versions.http4s,
          trace4cats %% "trace4cats-opentelemetry-otlp-grpc-exporter" % Versions.trace4CatsGRPCExporter
        )
      }
    )
    .dependsOn(fs2Kafka)

lazy val http4s = project
  .in(file("http4s"))
  .settings(kindProjectorSettings *)
  .settings(
    name := "trace4cats-zio-extras-http4s",
    libraryDependencies ++= {
      val trace4Cats = "io.janstenpickle"
      val http4s     = "org.http4s"

      Seq(
        trace4Cats %% "trace4cats-http4s-common" % Versions.trace4CatsHttp4sCommon,
        http4s     %% "http4s-client"            % Versions.http4s
      )
    }
  )
  .dependsOn(core % "compile->compile;test->test")

lazy val http4sExample =
  project
    .in(file("http4s-examples"))
    .settings(kindProjectorSettings *)
    .settings(
      name           := "trace4cats-zio-extras-http4s-examples",
      publish / skip := true,
      libraryDependencies ++= {
        val http4s     = "org.http4s"
        val trace4cats = "io.janstenpickle"

        Seq(
          http4s     %% "http4s-ember-server"                         % Versions.http4s,
          http4s     %% "http4s-ember-client"                         % Versions.http4s,
          trace4cats %% "trace4cats-opentelemetry-otlp-grpc-exporter" % Versions.trace4CatsGRPCExporter
        )
      }
    )
    .dependsOn(http4s)

lazy val zioHttp =
  project
    .in(file("zio-http"))
    .settings(kindProjectorSettings *)
    .settings(
      tpolecatExcludeOptions += ScalacOptions.lintInferAny
    ) // zio-http's @@ causes this (Scala 2.13) unless explicitly typed
    .settings(
      name                             := "trace4cats-zio-extras-zio-http",
      libraryDependencies += "dev.zio" %% "zio-http" % Versions.zioHttp
    )
    .dependsOn(core % "compile->compile;test->test")

lazy val zioHttpExample =
  project
    .in(file("zio-http-examples"))
    .settings(kindProjectorSettings *)
    .settings(
      tpolecatExcludeOptions += ScalacOptions.lintInferAny
    ) // zio-http's @@ causes this (Scala 2.13) unless explicitly typed
    .settings(
      name           := "trace4cats-zio-extras-zio-http-examples",
      publish / skip := true,
      libraryDependencies ++= {
        val trace4cats = "io.janstenpickle"
        Seq(
          trace4cats      %% "trace4cats-opentelemetry-otlp-grpc-exporter" % Versions.trace4CatsGRPCExporter,
          "dev.zio"       %% "zio-logging-slf4j"                           % Versions.zioLogging,
          "ch.qos.logback" % "logback-classic"                             % Versions.logback
        )
      }
    )
    .dependsOn(zioHttp)

lazy val caliban =
  project
    .in(file("caliban"))
    .settings(kindProjectorSettings *)
    .settings(
      tpolecatExcludeOptions += ScalacOptions.lintInferAny
    )
    .settings(
      name := "trace4cats-zio-extras-caliban",
      libraryDependencies ++= Seq(
        "com.github.ghostdogpr" %% "caliban"       % Versions.caliban,
        "com.github.ghostdogpr" %% "caliban-tools" % Versions.caliban
      )
    )
    .dependsOn(zquery % "compile->compile;test->test")

lazy val sttp =
  project
    .in(file("sttp"))
    .settings(kindProjectorSettings *)
    .settings(
      name                                                   := "trace4cats-zio-extras-sttp",
      libraryDependencies += "com.softwaremill.sttp.client3" %% "zio" % Versions.sttp,
      // Prevents org.scala-lang.modules:scala-collection-compat _3, _2.13 conflicting cross-version suffixes
      excludeDependencies += "org.scala-lang.modules" % "scala-collection-compat_2.13"
    )
    .dependsOn(core % "compile->compile;test->test")

lazy val sttpExample =
  project
    .in(file("sttp-examples"))
    .settings(kindProjectorSettings *)
    .settings(
      name           := "trace4cats-zio-extras-zio-sttp-examples",
      publish / skip := true,
      libraryDependencies ++= Seq(
        "io.janstenpickle" %% "trace4cats-opentelemetry-otlp-grpc-exporter" % Versions.trace4CatsGRPCExporter
      ),
      // Prevents org.scala-lang.modules:scala-collection-compat _3, _2.13 conflicting cross-version suffixes
      excludeDependencies += "org.scala-lang.modules" % "scala-collection-compat_2.13"
    )
    .dependsOn(sttp)

lazy val sttp4 =
  project
    .in(file("sttp4"))
    .settings(kindProjectorSettings *)
    .settings(
      name                                                   := "trace4cats-zio-extras-sttp4",
      libraryDependencies += "com.softwaremill.sttp.client4" %% "zio" % Versions.sttp4,
      // Prevents org.scala-lang.modules:scala-collection-compat _3, _2.13 conflicting cross-version suffixes
      excludeDependencies += "org.scala-lang.modules" % "scala-collection-compat_2.13"
    )
    .dependsOn(core % "compile->compile;test->test")

lazy val sttp4Example =
  project
    .in(file("sttp4-examples"))
    .settings(kindProjectorSettings *)
    .settings(
      name           := "trace4cats-zio-extras-zio-sttp4-examples",
      publish / skip := true,
      libraryDependencies ++= Seq(
        "io.janstenpickle" %% "trace4cats-opentelemetry-otlp-grpc-exporter" % Versions.trace4CatsGRPCExporter
      ),
      // Prevents org.scala-lang.modules:scala-collection-compat _3, _2.13 conflicting cross-version suffixes
      excludeDependencies += "org.scala-lang.modules" % "scala-collection-compat_2.13"
    )
    .dependsOn(sttp4)

lazy val tapir =
  project
    .in(file("tapir"))
    .settings(kindProjectorSettings *)
    .settings(
      name := "trace4cats-zio-extras-tapir",
      libraryDependencies ++=
        Seq(
          "com.softwaremill.sttp.tapir" %% "tapir-server"        % Versions.tapir,
          "com.softwaremill.sttp.tapir" %% "tapir-zio"           % Versions.tapir % Test,
          "com.softwaremill.sttp.tapir" %% "tapir-http4s-server" % Versions.tapir % Test
        )
    )
    .dependsOn(core % "compile->compile;test->test")

lazy val tapirExample =
  project
    .in(file("tapir-examples"))
    .settings(
      name           := "trace4cats-zio-extras-zio--sttp-examples",
      publish / skip := true,
      libraryDependencies ++=
        Seq(
          "io.janstenpickle"            %% "trace4cats-opentelemetry-otlp-grpc-exporter" % Versions.trace4CatsGRPCExporter,
          "com.softwaremill.sttp.tapir" %% "tapir-json-circe"                            % Versions.tapir,
          "com.softwaremill.sttp.tapir" %% "tapir-zio"                                   % Versions.tapir,
          "com.softwaremill.sttp.tapir" %% "tapir-http4s-server"                         % Versions.tapir,
          "org.http4s"                  %% "http4s-ember-server"                         % Versions.http4s
        ),
      publish / skip := true
    )
    .dependsOn(tapir)

lazy val virgil =
  project
    .in(file("virgil"))
    .settings(kindProjectorSettings *)
    .settings(
      name                                         := "trace4cats-zio-extras-virgil",
      libraryDependencies += "io.kaizen-solutions" %% "virgil-zio" % Versions.virgil
    )
    .dependsOn(core % "compile->compile;test->test")

lazy val virgilExample =
  project
    .in(file("virgil-examples"))
    .settings(kindProjectorSettings *)
    .settings(
      name                                      := "trace4cats-zio-extras-virgil-examples",
      libraryDependencies += "io.janstenpickle" %% "trace4cats-opentelemetry-otlp-grpc-exporter" % Versions.trace4CatsGRPCExporter,
      publish / skip                            := true
    )
    .dependsOn(core, virgil)

lazy val doobie =
  project
    .in(file("doobie"))
    .settings(kindProjectorSettings *)
    .settings(
      name := "trace4cats-zio-extras-doobie",
      libraryDependencies ++=
        Seq(
          "org.tpolecat"          %% "doobie-core"                    % Versions.doobie,
          "org.tpolecat"          %% "doobie-postgres"                % Versions.doobie           % Test,
          "io.zonky.test"          % "embedded-postgres"              % Versions.embeddedPostgres % Test,
          "io.zonky.test.postgres" % "embedded-postgres-binaries-bom" % "17.5.0"                  % Test
        )
    )
    .dependsOn(core % "compile->compile;test->test")

lazy val doobieExample =
  project
    .in(file("doobie-examples"))
    .settings(kindProjectorSettings *)
    .settings(
      name           := "doobie-examples",
      publish / skip := true,
      libraryDependencies ++= Seq(
        "io.janstenpickle" %% "trace4cats-opentelemetry-otlp-grpc-exporter" % Versions.trace4CatsGRPCExporter,
        "org.tpolecat"     %% "doobie-postgres"                             % Versions.doobie
      ),
      publish / skip := true
    )
    .dependsOn(core, doobie)

lazy val skunk =
  project
    .in(file("skunk"))
    .settings(kindProjectorSettings *)
    .settings(
      name := "trace4cats-zio-extras-skunk",
      libraryDependencies ++=
        Seq(
          "org.tpolecat"  %% "skunk-core"        % Versions.skunk,
          "io.zonky.test"  % "embedded-postgres" % Versions.embeddedPostgres % Test,
          "dev.zio"       %% "zio-logging-slf4j" % Versions.zioLogging       % Test,
          "ch.qos.logback" % "logback-classic"   % Versions.logback          % Test
        )
    )
    .dependsOn(
      core % "compile->compile;test->test",
      fs2
    )

lazy val skunkExample =
  project
    .in(file("skunk-examples"))
    .settings(kindProjectorSettings *)
    .settings(
      name           := "skunk-examples",
      publish / skip := true,
      libraryDependencies ++= Seq(
        "io.janstenpickle" %% "trace4cats-opentelemetry-otlp-grpc-exporter" % Versions.trace4CatsGRPCExporter
      )
    )
    .dependsOn(skunk)

lazy val zioKafka =
  mkModule("zio-kafka")
    .settings(
      libraryDependencies ++= Seq(
        "dev.zio"                 %% "zio-kafka"         % Versions.zioKafka,
        "io.github.embeddedkafka" %% "embedded-kafka"    % Versions.kafkaEmbedded % Test,
        "dev.zio"                 %% "zio-logging-slf4j" % Versions.zioLogging    % Test,
        "ch.qos.logback"           % "logback-classic"   % Versions.logback       % Test
      ),
      excludeDependencies ++= {
        CrossVersion.partialVersion(scalaVersion.value) match {
          case Some((3, _)) =>
            List("org.scala-lang.modules" %% "scala-collection-compat")
          case _ => Nil
        }
      }
    )
    .dependsOn(
      core % "compile->compile;test->test"
    )

lazy val zioKafkaExamples = {
  mkModule("zio-kafka-examples")
    .settings(
      publish / skip := true,
      libraryDependencies ++= Seq(
        "io.janstenpickle" %% "trace4cats-opentelemetry-otlp-grpc-exporter" % Versions.trace4CatsGRPCExporter,
        "dev.zio"          %% "zio-logging-slf4j"                           % Versions.zioLogging,
        "ch.qos.logback"    % "logback-classic"                             % Versions.logback
      )
    )
    .dependsOn(
      zioKafka
    )
}

lazy val docs =
  project
    .in(file("trace4cats-zio-extras-docs"))
    .enablePlugins(MdocPlugin)
    .settings(
      publish / skip := true,
      mdocVariables  := Map("VERSION" -> version.value),
      libraryDependencies ++= Seq(
        "com.softwaremill.sttp.tapir" %% "tapir-http4s-server" % Versions.tapir
      )
    )
    .dependsOn(
      core,
      zquery,
      fs2,
      fs2Kafka,
      http4s,
      http4sExample,
      zioHttp,
      caliban,
      sttp,
      sttpExample,
      sttp4,
      sttp4Example,
      tapir,
      tapirExample,
      virgil,
      doobie,
      skunk,
      zioKafka
    )
