val FpmsVersion = "0.1.0"
val FpmsScalaVersion = "3.0.2"

val Http4sVersion = "1.0.0-M29"
val CirceVersion = "0.14.1"
val Specs2Version = "4.1.0"
val LogbackVersion = "1.2.3"
val DoobieVersion = "1.0.0-RC1"

ThisBuild / scalafixDependencies += "com.github.liancheng" %% "organize-imports" % "0.4.4"
ThisBuild / version := FpmsVersion
ThisBuild / scalaVersion := FpmsScalaVersion
ThisBuild / scalacOptions := defaultscalacOptions
Compile / run / fork := true

lazy val common = (project in file("common")).settings(
  name := "fpms-common",
  libraryDependencies ++= CommonDeps ++ DoobieDeps ++ Seq("dev.profunktor" %% "redis4cats-effects" % "1.0.0")
)

lazy val calculator = (project in file("calculator"))
  .settings(
    name := "fmps-calcurator",
    fork := true,
    libraryDependencies ++= CommonDeps ++ Seq(
      "commons-io" % "commons-io" % "2.8.0",
      "com.github.scopt" %% "scopt" % "4.0.1",
      "com.github.cb372" %% "cats-retry" % "3.1.0"
    ) ++ DoobieDeps,
    semanticdbEnabled := true,
    semanticdbVersion := scalafixSemanticdb.revision,
    javaOptions := Seq(
      "-verbose:gc.log",
      "-Xlog:gc*:file=./logs/gc/gc_%t_%p.log:time,uptime,level,tags",
      "-XX:+UseG1GC",
      "-XX:MaxRAMPercentage=75",
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
  "com.github.sh4869" %% "semver-parser-scala" % "0.0.6",
  "com.typesafe" % "config" % "1.4.0",
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.4",
  "ch.qos.logback" % "logback-classic" % LogbackVersion
) ++ CatsDeps ++ CirceDeps

lazy val CatsDeps = Seq(
  "org.typelevel" %% "cats-core" % "2.6.1",
  "org.typelevel" %% "cats-effect" % "3.1.1"
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
  "-feature",
  "-Xfatal-warnings",
  "-source:future"
)

assembly / assemblyMergeStrategy := {
  case "META-INF/io.netty.versions.properties" => MergeStrategy.concat
  case x =>
    val oldStrategy = (assembly / assemblyMergeStrategy).value
    oldStrategy(x)
}
