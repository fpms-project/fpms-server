package fpms

import com.gilt.gfc.semver.SemVer
import io.circe.Decoder
import io.circe.Encoder

case class PackageInfo(name: String, version: SemVer, dep: Map[String, String])

object PackageInfo {

  def apply(name: String, version: String, dep: Map[String, String]) =
    new PackageInfo(name, SemVer(version), dep)

  implicit val encoderPackageInfo: Encoder[PackageInfo] =
    Encoder.forProduct2("name", "version")(p => (p.name, p.version.original))

  implicit val decoderPackageInfo: Decoder[PackageInfo] =
    Decoder.forProduct3[PackageInfo, String, String, Map[String, String]]("name", "version", "dep")(
      PackageInfo.apply
    )
}

