package fpms

import com.gilt.gfc.semver.SemVer
import fpms.PackageDepRepository.PackageDepRepositoryError

case class PackageInfoBase(name: String,version: SemVer)

trait PackageDepRepository[F[_]] {
  def get(name: String): F[Either[PackageDepRepositoryError, Seq[PackageInfoBase]]]

  def add(name: String,info: PackageInfoBase): F[Either[PackageDepRepositoryError, ()]]
}

object PackageDepRepository {
  sealed trait PackageDepRepositoryError
}
