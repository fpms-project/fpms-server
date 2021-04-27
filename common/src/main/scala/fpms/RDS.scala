package fpms

case class RDS(src: Either[String, List[Int]]) {
  def toStr: String = src match {
    case Left(v)  => v
    case Right(v) => v.mkString(",")
  }

  def to: List[Int] = src match {
    case Right(v)  => v
    case Left(str) => str.split(",").map(_.toInt).toList
  }
}

object RDS {
  // どのぐらいの長さからStringに変換するか
  private val LENGTH_FOR_CONVERTING_TO_STRING = 900
  def apply(s: List[Int]): RDS =
    if (s.size > LENGTH_FOR_CONVERTING_TO_STRING) RDS(Left(s.mkString(","))) else RDS(Right(s))
  type RDSMap = Map[Int, RDS]
}
