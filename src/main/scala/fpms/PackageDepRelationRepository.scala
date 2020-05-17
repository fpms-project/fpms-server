package fpms

case class PackageInfoBase(name: String, version: String)

trait PackageDepRelationRepository[F[_]] {
  def get(name: String): F[Option[Seq[PackageInfoBase]]]

  def add(name: String, info: PackageInfoBase): F[Unit]
}
