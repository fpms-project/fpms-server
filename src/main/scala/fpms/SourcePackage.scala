package fpms

import io.circe.Json
import doobie._
import doobie.implicits._
import io.circe.generic.auto._
import io.circe.parser.decode
import SourcePackage._

case class SourcePackage(id: Int, name: String, version: String, deps: Json, deps_latest: Json) {
  def getDeps: Option[Deps] = deps.as[Deps].toOption

  def getDepsLatest: Option[DepsLatest] = deps_latest.as[DepsLatest].toOption
}

case class SourcePackageInfo(name: String, version: String, deps: Json)

case class LatestChild(version: String, id: Int)

object SourcePackage {
  type Deps = Map[String, String]
  type DepsLatest = Map[String, LatestChild]
}
