package fpms

import io.circe.{Decoder, Encoder}
import com.github.sh4869.semver_parser.SemVer

case class PackageInfo(name: String, version: SemVer, dep: Map[String, String]) {
  override def equals(obj: Any): Boolean =
    obj match {
      case d: PackageInfo => d.name == name && version == d.version
      case _              => false
    }
  def base = PackageInfoBase(name, version.toString())
  override def hashCode = name.## + version.##
}

object PackageInfo {

  def apply(name: String, version: String, dep: Option[Map[String, String]]) =
    new PackageInfo(name, SemVer(version), dep.getOrElse(Map.empty[String, String]))

  def apply(name: String, version: String, dep: Map[String, String]) =
    new PackageInfo(name, SemVer(version), dep)

  implicit val encoderPackageInfo: Encoder[PackageInfo] =
    Encoder.forProduct3("name", "version", "dep")(p => (p.name, p.version.original, p.dep))

  implicit val decoderPackageInfo: Decoder[PackageInfo] =
    Decoder.forProduct3[PackageInfo, String, String, Option[Map[String, String]]](
      "name",
      "version",
      "dep"
    )(
      PackageInfo.apply
    )
}
