package fpms.redis

import scala.util.Try

object RedisDataConversion {
  implicit class SeqString(private val src: String) extends AnyVal {
    def splitToSeq: Seq[Int] = src.split(",").map(src => Try { Some(src.toInt) }.getOrElse(None)).flatten.toSeq
  }

  implicit class SetString(private val src: String) extends AnyVal {
    def splitToSet: Set[Int] = src.split(",").map(src => Try { Some(src.toInt) }.getOrElse(None)).flatten.toSet
  }
}
