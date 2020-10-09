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
import io.circe.Encoder

case class SourcePackageSave(name: String, version: String, deps: Json, id: Int) {
  def to: SourcePackage = SourcePackage(name, SemVer(version), deps.as[Deps].toOption.getOrElse(Map.empty), id)
}

case class SourcePackage(name: String, version: SemVer, deps: Deps, id: Int) {
  def to: SourcePackageSave = SourcePackageSave(name, version.original, deps.asJson, id)
}

case class AddPackage(name: String, version: String, deps: Map[String, String])

case class LatestChild(version: String, id: Int)

object SourcePackage {
  type Deps = Map[String, String]

  def apply(name: String, version: String, dep: Option[Map[String, String]], id: Int) =
    new SourcePackage(name, SemVer(version), dep.getOrElse(Map.empty[String, String]), id)

  implicit class Versions(seq: Seq[SourcePackage]) {
    def latestInFits(condition: String): Option[SourcePackage] = {
      Try {
        val range = Range(condition)
        var x: Option[SourcePackage] = None
        for (i <- 0 to seq.length - 1) {
          if (range.valid(seq(i).version) && x.forall(v => seq(i).version > v.version)) {
            x = Some(seq(i))
          }
        }
        x
      }.getOrElse(None)
    }
  }
}
