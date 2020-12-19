package fpms.calcurator.rds

import scala.util.Try

import cats.effect.ConcurrentEffect
import cats.effect.ContextShift
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.effect.Log

import fpms.redis.RedisConf

class RDSContainerOnRedis[F[_]](conf: RedisConf)(implicit F: ConcurrentEffect[F], cs: ContextShift[F])
    extends RDSContainer[F]
    with LazyLogging {
  import LDILContainerOnRedis._

  implicit private val log: Log[F] = new Log[F] {
    def debug(msg: => String): F[Unit] = F.pure(logger.debug(msg))
    def error(msg: => String): F[Unit] = F.pure(logger.error(msg))
    def info(msg: => String): F[Unit] = F.pure(logger.info(msg))
  }

  def get(id: Int): F[Option[scala.collection.Set[Int]]] = Redis[F].utf8(s"redis://${conf.host}:${conf.port}").use {
    cmd =>
      for {
        x <- cmd.get(s"$prefix$id")
      } yield x.map(_.splitToSet)
  }

  def sync(map: RDSMap): F[Unit] =
    Redis[F].utf8(s"redis://${conf.host}:${conf.port}").use { cmd =>
      F.pure(map.grouped(10).zipWithIndex.foreach {
        case (v, i) => {
          if (i * 10 % 1000000 == 0) logger.info(s"${i * 10}")
          F.toIO(cmd.mSet(v.map { case (i, v) => (s"$prefix$i", v.mkString(",")) })).unsafeRunSync()
        }
      })
    }

  private val prefix = s"packages_"

}

object LDILContainerOnRedis {
  implicit class SetString(private val src: String) extends AnyVal {
    def splitToSet: Set[Int] = src.split(",").map(src => Try { Some(src.toInt) }.getOrElse(None)).flatten.toSet
  }
}
