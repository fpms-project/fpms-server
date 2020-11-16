package fpms

import com.github.sh4869.semver_parser.SemVer

case class LibraryPackage(name: String, version: SemVer, deps: Map[String, String], id: Int)

object LibraryPackage {
  def apply(name: String, version: String, dep: Option[Map[String, String]], id: Int) =
    new LibraryPackage(name, SemVer(version), dep.getOrElse(Map.empty[String, String]), id)
}
