package fpms

trait PackageAllDepRepository[F[_]] {
  def store(
      pack: PackageInfoBase,
      deps: Seq[PackageInfoBase]
  ): F[Unit]

  def storeMultiEmpty(b: Seq[PackageInfoBase]): F[Unit]

  def get(pack: PackageInfoBase): F[Option[Seq[PackageInfoBase]]]
}
