package fpms

import com.github.sh4869.semver_parser.SemVer
import io.circe.Encoder
import io.circe.Json
import io.circe.Decoder

case class LibraryPackage(name: String, version: SemVer, deps: Map[String, String], id: Int)

object LibraryPackage {
  def apply(name: String, version: String, dep: Option[Map[String, String]], id: Int) =
    new LibraryPackage(name, SemVer(version), dep.getOrElse(Map.empty[String, String]), id)

  def apply(name: String, version: String, dep: Map[String, String], id: Int) =
    new LibraryPackage(name, SemVer(version), dep, id)

  implicit val encoder: Encoder[LibraryPackage] = new Encoder[LibraryPackage] {
    final def apply(a: LibraryPackage): Json =
      Json.obj(
        ("name", Json.fromString(a.name)),
        ("version", Json.fromString(a.version.original)),
        ("dep", Json.obj(a.deps.map(x => (x._1, Json.fromString(x._2))).toSeq: _*))
      )
  }

  implicit val decoder: Decoder[LibraryPackage] =
    Decoder.forProduct4[LibraryPackage, String, String, Map[String, String], Int]("name", "version", "deps", "id")(
      LibraryPackage.apply
    )
}
