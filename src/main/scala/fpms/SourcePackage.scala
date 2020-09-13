package fpms

import io.circe.Json
import doobie._
import doobie.implicits._
import io.circe.generic.auto._
import io.circe.syntax._
import io.circe.parser.decode
import SourcePackage._
import com.github.sh4869.semver_parser.{SemVer, Range}
import scala.util.Try

case class SourcePackage(id: Int, name: String, version: String, deps: Json, deps_latest: Json) {
  def getDeps: Option[Deps] = deps.as[Deps].toOption

  def getDepsLatest: Option[DepsLatest] = deps_latest.as[DepsLatest].toOption
}

case class SourcePackageInfoSave(name: String, version: String, desp: Json, id: Int)

case class SourcePackageInfo(name: String, version: SemVer, deps: Deps, id: Int) {
  def to: SourcePackageInfoSave = SourcePackageInfoSave(name, version.original, deps.asJson, id)
}

case class LatestChild(version: String, id: Int)

object SourcePackage {
  type Deps = Map[String, String]
  type DepsLatest = Map[String, LatestChild]
}

object SourcePackageInfo {
  def apply(name: String, version: String, dep: Option[Map[String, String]], id: Int) =
    new SourcePackageInfo(name, SemVer(version), dep.getOrElse(Map.empty[String, String]), id)

  implicit class Versions(seq: Seq[SourcePackageInfo]) {
    def latestInFits(condition: String): Option[SourcePackageInfo] = {
      Try {
        val range = Range(condition)
        for (i <- 0 to seq.length - 1) {
          if (range.valid(seq(i).version)) {
            return Some(seq(i))
          }
        }
        None
      }.getOrElse(None)
    }
  }
}
