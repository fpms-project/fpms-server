package fpms

case class RDS(src: String) {
  def to: Set[Int] = src.split(",").map(_.toInt).toSet
}
object RDS {
  def apply(s: Set[Int]): RDS = RDS(s.mkString(","))
  type RDSMap = Map[Int, RDS]
}
