package fpms.repository.redis

import cats.Parallel
import cats.implicits.*
import cats.effect.implicits.*
import com.typesafe.scalalogging.LazyLogging
import dev.profunktor.redis4cats.RedisCommands

import fpms.RDS
import fpms.repository.RDSRepository
import fpms.repository.redis.RedisDataConversion.*
import cats.effect.kernel.Async

class RDSRedisRepository[F[_]: Async](conf: RedisConfig)(implicit P: Parallel[F])
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
      miniMap.grouped(100).map { v => 
        cmd.mSet(v.map { case (i, v) => (key(i), v.mkString(",")) })
      }.toList.sequence_
    }
    indexed.map {
      case (m, i) =>
        Async[F].async_[Unit](cb => {
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
