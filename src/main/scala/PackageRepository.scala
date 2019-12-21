package package_manager_server

trait PackageRepository[F[_]] {
  def get(name: String): F[Seq[PackageInfo]]

  def store(pack: PackageInfo): F[Unit]
}
