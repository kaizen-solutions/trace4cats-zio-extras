ThisBuild / version            := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion       := "2.13.8"
ThisBuild / crossScalaVersions := Seq("2.12.15")
ThisBuild / scalacOptions ++= Seq(
)

lazy val root = project
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
      val trace4catsV = "0.12.0"
      val zioV        = "1.0.14"
      val zioInteropV = "3.2.9.1" // upgrade to 2 when published
      Seq(
        liHaoyi    %% "sourcecode"        % sourceCodeV,
        trace4cats %% "trace4cats-core"   % trace4catsV,
        trace4cats %% "trace4cats-inject" % trace4catsV,
        typelevel  %% "cats-effect"       % catsEffectV,
        zio        %% "zio"               % zioV,
        zio        %% "zio-interop-cats"  % zioInteropV
      )
    }
  )
