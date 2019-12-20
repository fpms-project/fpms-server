package package_manager_server

trait PackageRepository {
  def get(name: String): Seq[CodePackage]

  def store(pack: CodePackage): Option[Unit]
}
