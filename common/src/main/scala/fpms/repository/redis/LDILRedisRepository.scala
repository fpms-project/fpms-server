package fpms.repository.redis

import cats.Parallel
import cats.effect.ConcurrentEffect
import cats.effect.ContextShift
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging

import fpms.LDIL.LDILMap
import fpms.repository.LDILRepository

import RedisDataConversion._
import io.lettuce.core.protocol.RedisCommand

class LDILRedisRepository[F[_]](conf: RedisConf)(implicit F: ConcurrentEffect[F], cs: ContextShift[F], P: Parallel[F])
    extends LDILRepository[F]
    with LazyLogging
    with RedisLog[F] {
  protected val AforLog = implicitly

  def get(id: Int): F[Option[Seq[Int]]] =
    RedisResource.resource(conf).use { cmd => cmd.get(key(id)).map(_.map(_.splitToSeq)) }

  def get(ids: Seq[Int]): F[LDILMap] = RedisResource.resource(conf).use { cmd =>
    {
      // 16分割
      val grouped = ids.grouped(ids.size / 16).zipWithIndex
      grouped.map {
        case (seq, i) => {
          F.async[Map[Int, Seq[Int]]](cb => {
            // seq→Map[Int, Seq[Int]]
            val f = (seq: Seq[Int]) =>
              for {
                m <- cmd.mGet(seq.toSet.map(key(_)))
              } yield m.map {
                case (k, value) => k.split(prefix)(1).toInt -> value.splitToSeq
              }.toMap
            val x = seq.grouped(100).map(v => F.toIO(f(v)).unsafeRunSync())
            logger.info(s"end get thread $i")
            cb(Right(x.reduce((a, b) => a ++ b)))
          })
        }
      }.toList.parSequence.map(_.reduce((a, b) => a ++ b))
    }
  }

  def insert(id: Int, ldil: Seq[Int]): F[Unit] =
    RedisResource.resource(conf).use { cmd => cmd.set(key(id), value(ldil)) }

  def insert(map: LDILMap): F[Unit] = RedisResource.resource(conf).use { cmd =>
    {
      val grouped = map.grouped(map.size / 16).zipWithIndex
      grouped.map {
        case (miniMap, i) => {
          F.async[Unit](cb => {
            miniMap.grouped(100).foreach { v =>
              F.toIO(cmd.mSet(v.map { case (i, v) => (key(i), value(v)) })).unsafeRunSync()
            }
            logger.info(s"end insert $i")
            cb(Right(()))
          })
        }
      }
    }.toList.parSequence_
  }

  private val prefix = s"directed_"
  private def key(id: Int) = s"$prefix$id"
  private def value(ldil: Seq[Int]) = ldil.mkString(",")

}
