package fpms.calculator.package_map

import fpms.LibraryPackage

import PackageMapGenerator.NameToPackageListMap

trait PackageMapGenerator[F[_]] {
  def getMap: F[NameToPackageListMap]
}

object PackageMapGenerator {
  type NameToPackageListMap = Map[String, Seq[LibraryPackage]]
}