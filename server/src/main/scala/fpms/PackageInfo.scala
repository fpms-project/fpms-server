package fpms

import com.gilt.gfc.semver.SemVer
import io.circe.Decoder
import io.circe.Encoder

case class PackageInfo(name: String, version: SemVer, dep: Map[String, String])

object PackageInfo {

  def apply(name: String, version: String, dep: Map[String, String]) =
    new PackageInfo(name, SemVer(version), dep)

  implicit val encoderPackageInfo: Encoder[PackageInfo] =
    Encoder.forProduct3("name", "version", "dep")(p => (p.name, p.version.original, p.dep))

  implicit val decoderPackageInfo: Decoder[PackageInfo] =
    Decoder.forProduct3[PackageInfo, String, String, Map[String, String]]("name", "version", "dep")(
      PackageInfo.apply
    )
}

