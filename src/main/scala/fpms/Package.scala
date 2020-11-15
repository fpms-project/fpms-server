package fpms

import io.circe.Json
import doobie._
import doobie.implicits._
import io.circe.generic.auto._
import io.circe.syntax._
import io.circe.parser.decode
import Package._
import com.github.sh4869.semver_parser.{SemVer, Range}
import scala.util.Try
import io.circe.Encoder

case class Package(name: String, version: SemVer, deps: Deps, id: Int)

case class AddPackage(name: String, version: String, deps: Map[String, String])

object Package {
  type Deps = Map[String, String]

  def apply(name: String, version: String, dep: Option[Map[String, String]], id: Int) =
    new Package(name, SemVer(version), dep.getOrElse(Map.empty[String, String]), id)

  implicit class Versions(seq: Seq[Package]) {
    def latestInFits(condition: String): Option[Package] = {
      Try {
        val range = Range(condition)
        var x: Option[Package] = None
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
