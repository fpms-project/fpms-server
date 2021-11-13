package fpms.calculator.package_map

import fpms.LibraryPackage

import PackageMapGenerator.NameToPackageListMap

/** パッケージ名→パッケージのリストの配列を作成するGenerator
  */
trait PackageMapGenerator[F[_]] {

  /** F[NameToPackageListMap]を返す */
  def getMap: F[NameToPackageListMap]
}

object PackageMapGenerator {
  type NameToPackageListMap = Map[String, Seq[LibraryPackage]]
}
