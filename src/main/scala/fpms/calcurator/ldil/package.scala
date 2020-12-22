package fpms.calcurator

package object ldil {
  type LDILMap = Map[Int, List[Int]]

  private[ldil] val LDIL_REDIS_PREFIX = "directed_"  
}
