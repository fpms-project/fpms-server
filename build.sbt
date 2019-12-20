
val Http4sVersion = "0.20.0"

val CirceVersion = "0.11.1"

val Specs2Version = "4.1.0"

val LogbackVersion = "1.2.3"

lazy val root = (project in file(".")).settings(
  name := "package-manager-server",
  version := "0.1",
  scalaVersion := "2.12.10",
  libraryDependencies ++= Seq(
    "org.http4s" %% "http4s-blaze-server" % Http4sVersion,
    "org.http4s" %% "http4s-blaze-client" % Http4sVersion,
    "org.http4s" %% "http4s-circe" % Http4sVersion,
    "org.http4s" %% "http4s-dsl" % Http4sVersion,
    "io.circe" %% "circe-generic" % CirceVersion,
    "org.specs2" %% "specs2-core" % Specs2Version % "test",
    "ch.qos.logback" % "logback-classic" % LogbackVersion,
    "org.typelevel" %% "cats-effect" % "2.0.0",
    "com.gilt" %% "gfc-semver" % "0.0.5",
    "co.fs2" %% "fs2-core" % "2.1.0",
    "co.fs2" %% "fs2-io" % "2.1.0",
    "co.fs2" %% "fs2-reactive-streams" % "2.1.0",
    "co.fs2" %% "fs2-experimental" % "2.1.0"
  ),
  addCompilerPlugin("org.typelevel" %% "kind-projector"     % "0.10.3"),
  addCompilerPlugin("com.olegpy"    %% "better-monadic-for" % "0.3.0")
)


scalacOptions ++= Seq(
  "-deprecation",
  "-encoding", "UTF-8",
  "-language:higherKinds",
  "-language:postfixOps",
  "-language:higherKinds",
  "-feature",
  "-Ypartial-unification",
  "-Xfatal-warnings",
)
