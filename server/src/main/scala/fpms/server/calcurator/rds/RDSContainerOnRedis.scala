package fpms.server.calcurator.rds

import cats.Parallel
import cats.effect.ConcurrentEffect
import cats.effect.ContextShift
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import dev.profunktor.redis4cats.Redis

import fpms.server.redis.RedisConf
import fpms.server.redis.RedisLog

class RDSContainerOnRedis[F[_]](conf: RedisConf)(implicit F: ConcurrentEffect[F], cs: ContextShift[F], P: Parallel[F])
    extends RDSContainer[F]
    with LazyLogging
    with RedisLog[F] {
  import fpms.server.redis.RedisDataConversion._

  protected val X = implicitly

  def get(id: Int): F[Option[scala.collection.Set[Int]]] = Redis[F].utf8(s"redis://${conf.host}:${conf.port}").use {
    cmd =>
      for {
        x <- cmd.get(s"$prefix$id")
      } yield x.map(_.splitToSet)
  }

  def sync(map: RDSMap): F[Unit] =
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

  private val prefix = s"packages_"

}
