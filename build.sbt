inThisBuild {
  val scala212 = "2.12.15"
  val scala213 = "2.13.8"
  Seq(
    scalaVersion       := scala213,
    crossScalaVersions := Seq(scala212, scala213),
    scalacOptions += "-Xsource:3",
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
  )
}

lazy val root =
  project
    .in(file("."))
    .settings(publish / skip := true)
    .aggregate(core, http4s)

lazy val core = project
  .in(file("core"))
  .settings(
    name                              := "trace4cats-zio-extras",
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
  .settings(
    name                              := "trace4cats-zio-extras-http4s",
    organization                      := "io.kaizen-solutions",
    addCompilerPlugin(("org.typelevel" % "kind-projector" % "0.13.2").cross(CrossVersion.full)),
    libraryDependencies ++= Seq("io.janstenpickle" %% "trace4cats-http4s-common" % "0.13.1")
  )
  .dependsOn(core)
