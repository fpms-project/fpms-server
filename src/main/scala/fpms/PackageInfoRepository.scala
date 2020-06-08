package fpms

trait PackageInfoRepository[F[_]] {
  def store(info: PackageInfo): F[Unit]

  def storeVersions(infos: Seq[PackageInfo]): F[Unit]

  def get(name: String, version: String): F[Option[PackageInfo]]

  def getVersions(name: String): F[Option[Seq[PackageInfo]]]

  def has(name: String): F[Boolean]

}

object PackageInfoRepository {

  sealed trait PackageInfoRepositoryError

  case class OtherError(reason: String) extends PackageInfoRepositoryError

}
