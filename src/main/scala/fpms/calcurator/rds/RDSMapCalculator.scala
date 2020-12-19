package fpms.calcurator.rds

import fpms.calcurator.ldil.LDILMap

trait RDSMapCalculator[F[_]] {
  def calc(ldilMap: LDILMap): F[RDSMap]
}
