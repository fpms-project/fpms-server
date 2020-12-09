package fpms.calcurator.rds

import fpms.calcurator.ldil.LDILMap

trait RDSMapCalcurator[F[_]] {
  def calc(ldilMap: LDILMap): F[RDSMap]
}
