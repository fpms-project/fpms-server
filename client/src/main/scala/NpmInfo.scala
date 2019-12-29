case class RootInterface (
  name: String,
  versions: Seq[Versions]
)

case class Versions (
  version: String,
  dep: Map[String, String]
)

