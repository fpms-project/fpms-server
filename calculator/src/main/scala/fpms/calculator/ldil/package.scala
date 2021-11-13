package fpms.server.calcurator

package object ldil {
  // Latest Dependency Id List
  type LDILMap = Map[Int, Seq[Int]]

  private[ldil] val LDIL_REDIS_PREFIX = "directed_"
}
