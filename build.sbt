import sbt.Def
import sbtrelease.ReleaseStateTransformations._

inThisBuild {
  val scala212 = "2.12.15"
  val scala213 = "2.13.8"
  Seq(
    scalaVersion                        := scala213,
    crossScalaVersions                  := Seq(scala212, scala213),
    githubWorkflowPublishTargetBranches := Seq.empty,
    scalacOptions += "-Xsource:3",
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
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
    )
  )

lazy val root =
  project
    .in(file("."))
    .settings(publish / skip := true)
    .aggregate(core, http4s, http4sExample, zioHttp, zioHttpExample, sttp, sttpExample)

lazy val core = project
  .in(file("core"))
  .settings(releaseSettings: _*)
  .settings(
    name                              := "trace4cats-zio-extras-core",
    organization                      := "io.kaizen-solutions",
    addCompilerPlugin(("org.typelevel" % "kind-projector" % "0.13.2").cross(CrossVersion.full)),
    libraryDependencies ++= {
      val liHaoyi    = "com.lihaoyi"
      val trace4cats = "io.janstenpickle"
      val typelevel  = "org.typelevel"
      val zio        = "dev.zio"

      val catsEffectV = "3.3.11"
      val sourceCodeV = "0.2.8"
      val trace4catsV = "0.13.1"
      val zioV        = "1.0.14"
      val zioInteropV = "3.2.9.1" // upgrade to 2 when published
      Seq(
        liHaoyi    %% "sourcecode"        % sourceCodeV,
        trace4cats %% "trace4cats-core"   % trace4catsV,
        trace4cats %% "trace4cats-inject" % trace4catsV,
        typelevel  %% "cats-effect"       % catsEffectV,
        zio        %% "zio"               % zioV,
        zio        %% "zio-interop-cats"  % zioInteropV,
        zio        %% "zio-test"          % zioV % Test,
        zio        %% "zio-test-sbt"      % zioV % Test
      )
    }
  )

lazy val http4s = project
  .in(file("http4s"))
  .settings(releaseSettings: _*)
  .settings(
    name                              := "trace4cats-zio-extras-http4s",
    organization                      := "io.kaizen-solutions",
    addCompilerPlugin(("org.typelevel" % "kind-projector" % "0.13.2").cross(CrossVersion.full)),
    libraryDependencies ++= {
      val trace4Cats = "io.janstenpickle"
      val http4s     = "org.http4s"

      val trace4CatsV = "0.13.1"
      val http4sV     = "0.23.11"
      Seq(
        trace4Cats %% "trace4cats-http4s-common" % trace4CatsV,
        http4s     %% "http4s-client"            % http4sV
      )
    }
  )
  .dependsOn(core)

lazy val http4sExample =
  project
    .in(file("http4s-examples"))
    .settings(
      name                              := "trace4cats-zio-extras-http4s-examples",
      organization                      := "io.kaizen-solutions",
      publish / skip                    := true,
      addCompilerPlugin(("org.typelevel" % "kind-projector" % "0.13.2").cross(CrossVersion.full)),
      libraryDependencies ++= {
        val http4s     = "org.http4s"
        val trace4cats = "io.janstenpickle"

        val http4sV     = "0.23.11"
        val trace4catsV = "0.13.1"
        Seq(
          http4s     %% "http4s-blaze-server"               % http4sV,
          http4s     %% "http4s-blaze-client"               % http4sV,
          trace4cats %% "trace4cats-newrelic-http-exporter" % trace4catsV
        )
      }
    )
    .dependsOn(http4s)

lazy val zioHttp =
  project
    .in(file("zio-http"))
    .settings(releaseSettings: _*)
    .settings(
      name                              := "trace4cats-zio-extras-zio-http",
      organization                      := "io.kaizen-solutions",
      addCompilerPlugin(("org.typelevel" % "kind-projector" % "0.13.2").cross(CrossVersion.full)),
      libraryDependencies += "io.d11"   %% "zhttp"          % "1.0.0.0-RC27"
    )
    .dependsOn(core)

lazy val zioHttpExample =
  project
    .in(file("zio-http-examples"))
    .settings(
      name                              := "trace4cats-zio-extras-zio-http-examples",
      organization                      := "io.kaizen-solutions",
      publish / skip                    := true,
      addCompilerPlugin(("org.typelevel" % "kind-projector" % "0.13.2").cross(CrossVersion.full)),
      libraryDependencies ++= {
        val http4s     = "org.http4s"
        val trace4cats = "io.janstenpickle"

        val http4sV     = "0.23.11"
        val trace4catsV = "0.13.1"
        Seq(
          http4s     %% "http4s-blaze-client"               % http4sV,
          trace4cats %% "trace4cats-newrelic-http-exporter" % trace4catsV
        )
      }
    )
    .dependsOn(zioHttp)

lazy val sttp =
  project
    .in(file("sttp"))
    .settings(releaseSettings: _*)
    .settings(
      name                                                   := "trace4cats-zio-extras-sttp",
      organization                                           := "io.kaizen-solutions",
      addCompilerPlugin(("org.typelevel"                      % "kind-projector"          % "0.13.2").cross(CrossVersion.full)),
      libraryDependencies += "com.softwaremill.sttp.client3" %% "httpclient-backend-zio1" % "3.5.2"
    )
    .dependsOn(core)

lazy val sttpExample =
  project
    .in(file("sttp-examples"))
    .settings(
      name                              := "trace4cats-zio-extras-zio-sttp-examples",
      organization                      := "io.kaizen-solutions",
      publish / skip                    := true,
      addCompilerPlugin(("org.typelevel" % "kind-projector" % "0.13.2").cross(CrossVersion.full)),
      libraryDependencies ++= Seq(
        "io.janstenpickle" %% "trace4cats-newrelic-http-exporter" % "0.13.1",
        "org.http4s"       %% "http4s-blaze-client"               % "0.23.11"
      )
    )
    .dependsOn(sttp)
