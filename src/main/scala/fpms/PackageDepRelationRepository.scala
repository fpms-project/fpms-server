package fpms

case class PackageInfoBase(name: String, version: String) {
  override def toString: String = s"$name@$version"
}

trait PackageDepRelationRepository[F[_]] {
  def get(name: String): F[Option[Seq[PackageInfoBase]]]

  def add(name: String, info: PackageInfoBase): F[Unit]
  def addMulti(target: Seq[(String, PackageInfoBase)]): F[Unit]
}
