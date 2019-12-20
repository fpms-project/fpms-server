package package_manager_server

import fs2.Stream

class PackageManager(packageRepository: PackageRepository,refPackageRepository: RefPackageRepository) {
  def addVersion(pack: CodePackage) = Stream(pack).evalMap { pack =>
    for {
      _ <- packageRepository.store(pack)
      _ <- pack.dependencies.map { dep =>
        refPackageRepository.add(pack.info.name,RefPackage())
      }
    } yield pack

  }
}
