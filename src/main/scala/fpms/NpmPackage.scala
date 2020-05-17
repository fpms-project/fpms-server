package fpms

case class RootInterface (
  name: String,
  versions: Seq[NpmPackageVersion]
)

case class NpmPackageVersion (
  version: String,
  dep: Option[Map[String, String]]
)

