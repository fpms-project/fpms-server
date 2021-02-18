package fpms.repository.db

import com.github.sh4869.semver_parser.SemVer
import io.circe.Json
import io.circe.syntax._

import fpms.LibraryPackage

case class PackageSqlFormat(
    name: String,
    version: String,
    deps: Json,
    id: Int,
    shasum: String,
    integrity: Option[String]
) {
  def to: LibraryPackage =
    LibraryPackage(
      name,
      SemVer(version),
      deps.as[Map[String, String]].toOption.getOrElse(Map.empty),
      id,
      shasum,
      integrity
    )
}

object PackageSqlFormat {
  def from(pack: LibraryPackage): PackageSqlFormat =
    PackageSqlFormat(pack.name, pack.version.original, pack.deps.asJson, pack.id, pack.shasum, pack.integrity)
}
