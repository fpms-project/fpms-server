package fpms

case class RDS(src: Either[String, Set[Int]]) {
  def toStr: String = src match {
    case Left(v)  => v
    case Right(v) => v.mkString(",")
  }

  def to: Set[Int] = src match {
    case Right(v)  => v
    case Left(str) => str.split(",").map(_.toInt).toSet
  }
}
object RDS {
  def apply(s: Set[Int]): RDS = if (s.size > 750) RDS(Left(s.mkString(","))) else RDS(Right(s))
  type RDSMap = Map[Int, RDS]
}
