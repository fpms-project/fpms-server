package fpms.repository.redis

import cats.Parallel
import cats.effect.ConcurrentEffect
import cats.effect.ContextShift
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging

import fpms.RDS
import fpms.repository.RDSRepository
import fpms.repository.redis.RedisDataConversion._

class RDSRedisRepository[F[_]](conf: RedisConfig)(implicit F: ConcurrentEffect[F], cs: ContextShift[F], P: Parallel[F])
    extends RDSRepository[F]
    with LazyLogging
    with RedisLog[F] {
  protected val AforLog = implicitly

  def get(id: Int): F[Option[Set[Int]]] =
    RedisResource.resource(conf).use { cmd => cmd.get(key(id)).map(_.map(_.splitToSet)) }

  def insert(id: Int, rds: Set[Int]): F[Unit] =
    RedisResource.resource(conf).use { cmd => cmd.set(key(id), value(rds)) }

  def insert(map: RDS.RDSMap): F[Unit] = RedisResource.resource(conf).use { cmd =>
    {
      val indexed = map.grouped(map.size / 16).zipWithIndex
      indexed.map {
        case (miniMap, i) =>
          F.async[Unit](cb => {
            miniMap.grouped(100).foreach { v =>
              F.toIO(cmd.mSet(v.map { case (i, v) => (key(i), v.toStr) })).unsafeRunSync()
            }
            logger.info(s"end $i")
            cb(Right(()))
          })
      }.toList.parSequence_
    }
  }

  private val prefix = s"packages_"
  private def key(id: Int) = s"$prefix$id"
  private def value(rds: scala.collection.Set[Int]) = rds.mkString(",")

}
