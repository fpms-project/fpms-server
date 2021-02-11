package fpms.server.calcurator.rds

import fpms.server.calcurator.ldil.LDILMap

trait RDSMapCalculator[F[_]] {
  def calc(ldilMap: LDILMap): F[RDSMap]
}
