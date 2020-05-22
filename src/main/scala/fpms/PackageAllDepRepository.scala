package fpms

trait PackageAllDepRepository[F[_]] {
  def store(
      pack: PackageInfoBase,
      deps: Map[String,Seq[PackageInfoBase]]
  ): F[Unit]

  def storeMultiEmpty(b: Seq[PackageInfoBase]): F[Unit]

  def get(name: String, version: String): F[Option[Map[String,Seq[PackageInfoBase]]]]
}
