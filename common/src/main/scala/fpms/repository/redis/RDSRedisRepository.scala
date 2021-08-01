package fpms.repository.redis

import cats.Parallel
import cats.effect.ConcurrentEffect
import cats.effect.ContextShift
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import dev.profunktor.redis4cats.RedisCommands

import fpms.RDS
import fpms.repository.RDSRepository
import fpms.repository.redis.RedisDataConversion._

class RDSRedisRepository[F[_]](conf: RedisConfig)(implicit F: ConcurrentEffect[F], cs: ContextShift[F], P: Parallel[F])
    extends RDSRepository[F]
    with LazyLogging
    with RedisLog[F] {
  protected val AforLog = implicitly

  def get(id: Int): F[Option[Set[Int]]] =
    resource.use { _.get(key(id)).map(_.map(_.splitToSet)) }

  def insert(id: Int, rds: Set[Int]): F[Unit] =
    resource.use { _.set(key(id), value(rds)) }

  def insert(map: RDS.RDSMap): F[Unit] = resource.use(_insert(map))

  private def _insert(map: RDS.RDSMap)(cmd: RedisCommands[F, String, String]): F[Unit] = {
    val indexed = map.grouped(map.size / 16).zipWithIndex
    val insertFunc = (miniMap: Map[Int, Array[Int]]) => {
      miniMap.grouped(100).foreach { v =>
        F.toIO(cmd.mSet(v.map { case (i, v) => (key(i), v.mkString(",")) })).unsafeRunSync()
      }
    }
    indexed.map {
      case (m, i) =>
        F.async[Unit](cb => {
          insertFunc(m)
          logger.info(s"end $i")
          cb(Right(()))
        })
    }.toList.parSequence_
  }

  private lazy val resource = RedisResource.resource(conf)
  private val prefix = s"packages_"
  private def key(id: Int) = s"$prefix$id"
  private def value(rds: scala.collection.Set[Int]) = rds.mkString(",")

}
