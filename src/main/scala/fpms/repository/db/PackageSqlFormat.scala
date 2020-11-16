package fpms.repository.db

import com.github.sh4869.semver_parser.SemVer
import fpms.LibraryPackage
import io.circe.Json
import io.circe.syntax._

case class PackageSqlFormat(name: String, version: String, deps: Json, id: Int) {
  def to: LibraryPackage = LibraryPackage(name, SemVer(version), deps.as[Map[String, String]].toOption.getOrElse(Map.empty), id)
}

object PackageSqlFormat {
  def from(pack: LibraryPackage): PackageSqlFormat =
    PackageSqlFormat(pack.name, pack.version.original, pack.deps.asJson, pack.id)
}
