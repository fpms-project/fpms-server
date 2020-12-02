package fpms.calcurator.ldil

import fpms.LibraryPackage

trait LDILMapCalcurator[F[_]] {
  def init: F[Unit]

  def update(adds: Seq[LibraryPackage]): F[Unit]

  // TODO: ここは上手に考える
  def map: F[LDILMap]
}
