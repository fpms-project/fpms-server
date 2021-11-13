package fpms.calculator.rds

import fpms.RDS.RDSMap
import fpms.LDIL.LDILMap

trait RDSMapGenerator[F[_]] {
  def generate(ldilMap: LDILMap): F[RDSMap]
}
