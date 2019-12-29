
val Http4sVersion = "0.20.0"

val CirceVersion = "0.11.1"

val Specs2Version = "4.1.0"

val LogbackVersion = "1.2.3"


lazy val client = (project in file("client")).settings(
  name := "fmps-client",
  version := "0.1.0",
  scalaVersion := "2.12.10",
  libraryDependencies ++= http4sDeps ++ CirceDeps,
  addCompilerPlugin("org.typelevel" %% "kind-projector" % "0.10.3"),
  addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.0"),
  scalacOptions := defaultscalacOptions
).dependsOn(server)

lazy val server = (project in file("server")).settings(
  name := "fpms",
  version := "0.1",
  scalaVersion := "2.12.10",
  libraryDependencies ++= Seq(
    "org.specs2" %% "specs2-core" % Specs2Version % "test",
    "org.typelevel" %% "cats-effect" % "2.0.0",
    "org.typelevel" %% "cats-core" % "2.0.0",
    "org.typelevel" %% "cats-mtl-core" % "0.7.0",
    "com.gilt" %% "gfc-semver" % "0.0.5",
    "co.fs2" %% "fs2-core" % "2.1.0",
    "co.fs2" %% "fs2-io" % "2.1.0",
    "co.fs2" %% "fs2-reactive-streams" % "2.1.0",
    "co.fs2" %% "fs2-experimental" % "2.1.0",
    "dev.profunktor" %% "console4cats" % "0.8.0"
  ) ++ http4sDeps ++ CirceDeps,
  addCompilerPlugin("org.typelevel" %% "kind-projector" % "0.10.3"),
  addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.0"),
  scalacOptions := defaultscalacOptions
)


lazy val http4sDeps = Seq(
  "org.http4s" %% "http4s-blaze-server",
  "org.http4s" %% "http4s-blaze-client",
  "org.http4s" %% "http4s-circe",
  "org.http4s" %% "http4s-dsl"
).map(_ % Http4sVersion) ++ Seq("ch.qos.logback" % "logback-classic" % LogbackVersion)

lazy val CirceDeps = Seq(
  "io.circe" %% "circe-core",
  "io.circe" %% "circe-generic",
  "io.circe" %% "circe-generic-extras",
  "io.circe" %% "circe-parser"
).map(_  % CirceVersion)

lazy val defaultscalacOptions = Seq(
  "-deprecation",
  "-encoding", "UTF-8",
  "-language:higherKinds",
  "-language:postfixOps",
  "-language:higherKinds",
  "-feature",
  "-Ypartial-unification",
  "-Xfatal-warnings",
)
