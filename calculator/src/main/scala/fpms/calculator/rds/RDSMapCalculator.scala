package fpms.calculator.rds

import fpms.RDS.RDSMap
import fpms.LDIL.LDILMap

trait RDSMapCalculator[F[_]] {
  def calc(ldilMap: LDILMap): F[RDSMap]
}
