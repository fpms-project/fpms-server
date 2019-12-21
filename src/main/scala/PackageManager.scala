package package_manager_server

import cats.effect.ConcurrentEffect
import cats.implicits._

class PackageManager[F[_] : ConcurrentEffect](packageRepository: PackageRepository[F], refPackageRepository: RefPackageRepository) {
  def addVersion(pack: PackageInfo):F[Unit] =
    for {
      _ <- packageRepository.store(pack)
    } yield ()
  }
}
