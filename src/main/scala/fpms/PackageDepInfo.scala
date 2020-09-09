package fpms

import com.github.sh4869.semver_parser.SemVer
import io.circe.Decoder
import io.circe.Encoder

case class PackageDepInfo(name: String, version: SemVer, dep: Map[String, SemVer])

object PackageDepInfo {

  implicit val encoderPackageInfo: Encoder[PackageDepInfo] =
    Encoder.forProduct3("name", "version", "dep")(p => (p.name, p.version.original, p.dep.mapValues(_.original)))
}

