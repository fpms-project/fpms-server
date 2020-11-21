val Http4sVersion = "0.21.0"
val CirceVersion = "0.12.0"
val Specs2Version = "4.1.0"
val LogbackVersion = "1.2.3"
val DoobieVersion = "0.8.8"

Compile / run / fork := true

lazy val root = (project in file(".")).settings(
  name := "fpms",
  version := "0.1",
  scalaVersion := "2.12.12",
  fork in Runtime := true,
  libraryDependencies ++= Seq(
    "org.specs2" %% "specs2-core" % Specs2Version % "test",
    "org.typelevel" %% "cats-core" % "2.0.0",
    "org.typelevel" %% "cats-effect" % "2.0.0",
    "com.github.sh4869" %% "semver-parser-scala" % "0.0.3",
    "com.typesafe" % "config" % "1.4.0",
    "net.debasishg" %% "redisclient" % "3.30",
    "com.github.scopt" %% "scopt" % "3.7.1",
    "ch.qos.logback" % "logback-classic" % LogbackVersion,
    "com.typesafe.scala-logging" %% "scala-logging" % "3.9.2"
  ) ++ http4sDeps ++ CirceDeps ++ DoobieDeps,
  addCompilerPlugin("org.typelevel" %% "kind-projector" % "0.10.3"),
  addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.0"),
  scalacOptions := defaultscalacOptions
)

run / javaOptions := Seq(
  "-verbose:gc.log",
  "-Xlog:gc*:file=logs/gc/gc_%t_%p.log:time,uptime,level,tags",
  "-XX:+UseG1GC",
  "-XX:MaxRAMPercentage=80",
  "-XX:+TieredCompilation",
  "-XX:-UseCompressedOops",
  "-XX:HeapDumpPath=dump.log"
)

lazy val http4sDeps = Seq(
  "org.http4s" %% "http4s-blaze-server",
  "org.http4s" %% "http4s-blaze-client",
  "org.http4s" %% "http4s-circe",
  "org.http4s" %% "http4s-dsl"
).map(_ % Http4sVersion) ++ Seq(
  "ch.qos.logback" % "logback-classic" % LogbackVersion
)

lazy val CirceDeps = Seq(
  "io.circe" %% "circe-core",
  "io.circe" %% "circe-generic",
  "io.circe" %% "circe-generic-extras",
  "io.circe" %% "circe-parser"
).map(_ % CirceVersion)

lazy val DoobieDeps = Seq(
  "org.tpolecat" %% "doobie-core",
  "org.tpolecat" %% "doobie-hikari", // HikariCP transactor.
  "org.tpolecat" %% "doobie-postgres", // Postgres driver 42.2.9 + type mappings.
  "org.tpolecat" %% "doobie-postgres-circe" // Postgres driver 42.2.9 + type mappings.
).map(_ % DoobieVersion)

lazy val defaultscalacOptions = Seq(
  "-deprecation",
  "-encoding",
  "UTF-8",
  "-language:higherKinds",
  "-language:postfixOps",
  "-language:higherKinds",
  "-feature",
  "-Ypartial-unification",
  "-Xfatal-warnings",
  "log4j2.debug",
  "-Ywarn-unused"
)

assemblyMergeStrategy in assembly := {
  case "META-INF/io.netty.versions.properties" => MergeStrategy.concat
  case x =>
    val oldStrategy = (assemblyMergeStrategy in assembly).value
    oldStrategy(x)
}
