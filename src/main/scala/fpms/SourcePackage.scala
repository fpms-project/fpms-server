package fpms

import io.circe.Json
import doobie._
import doobie.implicits._
import io.circe.generic.auto._
import io.circe.syntax._
import io.circe.parser.decode
import SourcePackage._
import com.github.sh4869.semver_parser.SemVer

case class SourcePackage(id: Int, name: String, version: String, deps: Json, deps_latest: Json) {
  def getDeps: Option[Deps] = deps.as[Deps].toOption

  def getDepsLatest: Option[DepsLatest] = deps_latest.as[DepsLatest].toOption
}

case class SourcePackageInfoSave(name: String, version: SemVer, desp: Json, id: Int)

case class SourcePackageInfo(name: String, version: SemVer, deps: Deps, id: Int) {
  def to: SourcePackageInfoSave = SourcePackageInfoSave(name, version, deps.asJson, id)
}

case class LatestChild(version: String, id: Int)

object SourcePackage {
  type Deps = Map[String, String]
  type DepsLatest = Map[String, LatestChild]
}
