package fpms.repository.redis

import cats.Parallel
import cats.implicits.*
import cats.effect.implicits.*
import com.typesafe.scalalogging.LazyLogging
import dev.profunktor.redis4cats.RedisCommands

import fpms.LDIL.LDILMap
import fpms.repository.LDILRepository

import RedisDataConversion.*
import cats.effect.kernel.Async

class LDILRedisRepository[F[_]: Async](conf: RedisConfig)(implicit P: Parallel[F])
    extends LDILRepository[F]
    with LazyLogging
    with RedisLog[F] {

  protected val AforLog = implicitly

  def get(id: Int): F[Option[Seq[Int]]] =
    resource.use(_.get(key(id)).map(_.map(_.splitToSeq)))

  def get(ids: Seq[Int]): F[LDILMap] = resource.use(_get_seq(ids))

  private def _get_seq(ids: Seq[Int])(cmd: RedisCommands[F, String, String]): F[LDILMap] = {
    val grouped = ids.grouped(ids.size / 16).zipWithIndex
    val join = (seq: List[LDILMap]) => seq.reduce((a, b) => a ++ b)
    val getfunc = (subids: Seq[Int]) => {
      val f = (seq: Seq[Int]) => {
        val keys = seq.toSet.map(key(_))
        cmd.mGet(keys).map { result =>
          result.map { case (k, value) =>
            k.split(prefix)(1).toInt -> value.splitToSeq
          }.toMap
        }
      }
      subids.grouped(100).map(v => f(v)).toList.sequence.map(v => join(v))
    }
    grouped.map {
      case (seq, i) => {
        Async[F].async_[Map[Int, Seq[Int]]](cb => {
          for {
            x <- getfunc(seq)
          } yield {
            logger.info(s"end get ldils in thread ${i}")
            x
          }
        })
      }
    }.toList.parSequence.map(_.reduce((a, b) => a ++ b))
  }

  def insert(id: Int, ldil: Seq[Int]): F[Unit] =
    resource.use(_.set(key(id), value(ldil)))

  def insert(map: LDILMap): F[Unit] = resource.use(_insert(map))

  private def _insert(map: LDILMap)(cmd: RedisCommands[F, String, String]) = {
    val savefunc = (miniMap: LDILMap) => {
      miniMap
        .grouped(100)
        .map { v =>
          val kvmap = v.map { case (i, v) => (key(i), value(v)) }
          cmd.mSet(kvmap)
        }
        .toList
        .sequence_
    }
    val grouped = map.grouped(map.size / 16).zipWithIndex
    grouped.map {
      case (miniMap, i) => {
        savefunc(miniMap).map(v => logger.info(s"end insert $i"))
      }
    }.toList.parSequence_
  }

  private lazy val resource = RedisResource.resource(conf)
  private val prefix = s"directed_"
  private def key(id: Int) = s"$prefix$id"
  private def value(ldil: Seq[Int]) = ldil.mkString(",")

}
