package fpms.calculator.ldil

import fpms.LibraryPackage
import fpms.LDIL.LDILMap
import fpms.calculator.package_map.PackageMapGenerator

trait LDILMapCalculator[F[_]] {
  def init: F[LDILMap]

  def update(adds: Seq[LibraryPackage]): F[LDILMap]

}
