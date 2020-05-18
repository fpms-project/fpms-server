package fpms

trait PackageInfoRepository[F[_]] {
  def store(info: PackageInfo): F[Unit]

  def storeVersions(name: String, versions: Seq[String]): F[Unit]

  def get(name: String, version: String): F[Option[PackageInfo]]

  def getVersions(name: String): F[Option[Seq[String]]]

  def has(name: String): F[Boolean]

}

object PackageInfoRepository {

  sealed trait PackageInfoRepositoryError

  case class OtherError(reason: String) extends PackageInfoRepositoryError

}
