package fpms.calcurator.ldil

import fpms.LibraryPackage

trait LDILMapCalculator[F[_]] {
  def init: F[LDILMap]

  def update(adds: Seq[LibraryPackage]): F[LDILMap]

}
