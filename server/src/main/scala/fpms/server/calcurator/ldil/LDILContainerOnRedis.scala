package fpms.server.calcurator.ldil

import cats.Parallel
import cats.effect.ConcurrentEffect
import cats.effect.ContextShift
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import dev.profunktor.redis4cats.Redis

import fpms.server.redis.RedisConf
import fpms.server.redis.RedisLog

class LDILContainerOnRedis[F[_]](conf: RedisConf)(implicit F: ConcurrentEffect[F], cs: ContextShift[F], P: Parallel[F])
    extends LDILContainer[F]
    with LazyLogging
    with RedisLog[F] {
  import fpms.server.redis.RedisDataConversion._

  protected val X = implicitly

  def get(id: Int): F[Option[Seq[Int]]] = Redis[F].utf8(s"redis://${conf.host}:${conf.port}").use { cmd =>
    for {
      x <- cmd.get(s"$LDIL_REDIS_PREFIX$id")
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
                  val set = v.map { case (i, v) => (s"$LDIL_REDIS_PREFIX$i", v.mkString(",")) }
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

}
