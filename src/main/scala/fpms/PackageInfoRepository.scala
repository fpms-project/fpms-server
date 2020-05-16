package fpms

import com.gilt.gfc.semver.SemVer

trait PackageInfoRepository[F[_]] {

  import PackageInfoRepository._

  def store(info: PackageInfo): F[Either[PackageInfoRepositoryError, ()]]

  def get(name: String, version: SemVer): F[Either[PackageInfoRepositoryError, PackageInfo]]

  def getVersions(name: String): F[Either[PackageInfoRepositoryError, Seq[SemVer]]]
}

object PackageInfoRepository {

  sealed trait PackageInfoRepositoryError

  case class OtherError(reason: String) extends PackageInfoRepositoryError

}