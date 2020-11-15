package fpms

import com.github.sh4869.semver_parser.SemVer

case class Package(name: String, version: SemVer, deps: Map[String, String], id: Int)

object Package {
  def apply(name: String, version: String, dep: Option[Map[String, String]], id: Int) =
    new Package(name, SemVer(version), dep.getOrElse(Map.empty[String, String]), id)
}
