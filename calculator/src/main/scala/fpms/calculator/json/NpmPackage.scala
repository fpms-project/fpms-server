package fpms.calculator.json

import io.circe.Decoder
import io.circe.Json

case class RootInterface(
    name: String,
    versions: Seq[NpmPackageVersion]
)

case class RootInterfaceN(
    name: String,
    versions: Seq[NpmPackageWithId]
)

case class NpmPackageVersion(
    version: String,
    dep: Option[Map[String, String]],
    shasum: String,
    integrity: Option[String]
)

case class NpmPackageWithId(
    version: String,
    dep: Option[Map[String, String]],
    id: Int,
    shasum: String,
    integrity: Option[String]
)

object NpmPackageVersion {
  implicit val decodeNpmPackageVersion: Decoder[NpmPackageVersion] =
    Decoder.forProduct4[NpmPackageVersion, String, Option[Map[String, Json]], String, Option[String]](
      "version",
      "dep",
      "shasum",
      "integrity"
    )((version, depOp, shasum, integrity) =>
      NpmPackageVersion.apply(
        version,
        depOp.map(_.map(x => (x._1, x._2.as[String].getOrElse("RANGE_PARSE_ERROR")))),
        shasum,
        integrity
      )
    )
}
