package fpms.calcurator.ldil

import scala.util.Try

import cats.Parallel
import cats.effect.ConcurrentEffect
import cats.effect.ContextShift
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.effect.Log

import fpms.redis.RedisConf

class LDILContainerOnRedis[F[_]](conf: RedisConf)(implicit F: ConcurrentEffect[F], cs: ContextShift[F], P: Parallel[F])
    extends LDILContainer[F]
    with LazyLogging {
  import LDILContainerOnRedis._

  implicit private val log: Log[F] = new Log[F] {
    def debug(msg: => String): F[Unit] = F.pure(logger.debug(msg))
    def error(msg: => String): F[Unit] = F.pure(logger.error(msg))
    def info(msg: => String): F[Unit] = F.pure(logger.info(msg))
  }

  def get(id: Int): F[Option[Seq[Int]]] = Redis[F].utf8(s"redis://${conf.host}:${conf.port}").use { cmd =>
    for {
      x <- cmd.get(s"$prefix$id")
    } yield x.map(_.splitToSeq)
  }

  def sync(map: LDILMap): F[Unit] =
    Redis[F].utf8(s"redis://${conf.host}:${conf.port}").use { cmd =>
      map
        .grouped(map.size / 16)
        .zipWithIndex
        .map {
          case (miniMap, i) =>
            F.async[Unit](cb => {
              miniMap.grouped(100).foreach { v =>
                {
                  val set = v.map { case (i, v) => (s"$prefix$i", v.mkString(",")) }
                  F.toIO(cmd.mSet(set)).unsafeRunSync()
                }
              }
              logger.info(s"end $i")
              cb(Right(()))
            })
        }
        .toList
        .parSequence_
    }

  def update(subSet: Map[Int, List[Int]]): F[Unit] = ???

  private val prefix = s"directed_"

}

object LDILContainerOnRedis {
  implicit class SetString(private val src: String) extends AnyVal {
    def splitToSeq: Seq[Int] = src.split(",").map(src => Try { Some(src.toInt) }.getOrElse(None)).flatten.toSeq
  }
}
