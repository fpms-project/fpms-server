val FpmsVersion = "0.1.0"
val FpmsScalaVersion = "2.13.4"

val Http4sVersion = "0.21.0"
val CirceVersion = "0.12.0"
val Specs2Version = "4.1.0"
val LogbackVersion = "1.2.3"
val DoobieVersion = "0.8.8"
val CatsVersion = "2.3.0"

ThisBuild / scalafixDependencies += "com.github.liancheng" %% "organize-imports" % "0.4.4"
ThisBuild / version := FpmsVersion
ThisBuild / scalaVersion := FpmsScalaVersion
ThisBuild / scalacOptions := defaultscalacOptions
Compile / run / fork := true

lazy val common = (project in file("common")).settings(
  name := "fpms-common",
  libraryDependencies ++= CommonDeps ++ DoobieDeps ++ Seq("dev.profunktor" %% "redis4cats-effects" % "0.11.0")
)

lazy val calculator = (project in file("calculator"))
  .settings(
    name := "fmps-calcurator",
    fork := true,
    libraryDependencies ++= CommonDeps ++ Seq(
      "commons-io" % "commons-io" % "2.8.0",
      "com.github.scopt" %% "scopt" % "3.7.1"
    ) ++ DoobieDeps,
    semanticdbEnabled := true,
    semanticdbVersion := scalafixSemanticdb.revision,
    addCompilerPlugin("org.typelevel" %% "kind-projector" % "0.10.3"),
    addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.0"),
    javaOptions := Seq(
      "-verbose:gc.log",
      "-Xlog:gc*:file=./logs/gc/gc_%t_%p.log:time,uptime,level,tags",
      "-XX:+UseG1GC",
      "-XX:MaxRAMPercentage=80",
      "-XX:+TieredCompilation",
      "-XX:-UseCompressedOops",
      "-XX:MaxGCPauseMillis=10000",
      "-XX:HeapDumpPath=dump.log"
    )
  )
  .dependsOn(common)

lazy val server = (project in file("server"))
  .settings(
    name := "fpms-server",
    fork := true,
    libraryDependencies ++= CommonDeps ++ http4sDeps ++ DoobieDeps,
    semanticdbEnabled := true,
    semanticdbVersion := scalafixSemanticdb.revision,
    javaOptions := Seq(
      "-verbose:gc.log",
      "-Xlog:gc*:file=./logs/gc/gc_%t_%p.log:time,uptime,level,tags",
      "-XX:+TieredCompilation",
      "-XX:-UseCompressedOops",
      "-XX:HeapDumpPath=dump.log"
    )
  )
  .dependsOn(common)

lazy val CommonDeps = Seq(
  "com.github.sh4869" %% "semver-parser-scala" % "0.0.4",
  "com.typesafe" % "config" % "1.4.0",
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.2",
  "ch.qos.logback" % "logback-classic" % LogbackVersion
) ++ CatsDeps ++ CirceDeps

lazy val CatsDeps = Seq(
  "org.typelevel" %% "cats-core",
  "org.typelevel" %% "cats-effect"
).map(_ % CatsVersion)

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
