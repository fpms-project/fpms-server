package fpms.calcurator

package object ldil {
  type LDILMap = Map[Int, Seq[Int]]

  private[ldil] val LDIL_REDIS_PREFIX = "directed_"  
}
