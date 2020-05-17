package fpms

import io.circe.{Decoder, Encoder}

case class PackageInfo(name: String, version: String, dep: Map[String, String]) {
  override def equals(obj: Any): Boolean = obj match {
    case d: PackageInfo => d.name == name && version == d.version
    case _ => false
  }
}

object PackageInfo {

  def apply(name: String, version: String, dep: Option[Map[String, String]]) =
    new PackageInfo(name,version, dep.getOrElse(Map.empty[String, String]))

  def apply(name: String, version: String, dep: Map[String, String]) =
    new PackageInfo(name, version, dep)

  implicit val encoderPackageInfo: Encoder[PackageInfo] =
    Encoder.forProduct3("name", "version", "dep")(p => (p.name, p.version, p.dep))

  implicit val decoderPackageInfo: Decoder[PackageInfo] =
    Decoder.forProduct3[PackageInfo, String, String, Option[Map[String, String]]]("name", "version", "dep")(
      PackageInfo.apply
    )
}

