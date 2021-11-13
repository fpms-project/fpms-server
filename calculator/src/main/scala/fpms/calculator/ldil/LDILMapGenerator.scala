package fpms.calculator.ldil

import fpms.LibraryPackage
import fpms.LDIL.LDILMap
import fpms.calculator.package_map.PackageMapGenerator

trait LDILMapGenerator[F[_]] {

  /** パッケージID→そのパッケージの直接依存のIDのリストを取得
    */
  def init: F[LDILMap]

  def update(adds: Seq[LibraryPackage]): F[LDILMap]
}
