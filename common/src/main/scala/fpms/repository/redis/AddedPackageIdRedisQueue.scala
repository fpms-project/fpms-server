package fpms.repository.redis

import cats.effect.ConcurrentEffect
import cats.effect.ContextShift
import cats.effect.Timer
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import dev.profunktor.redis4cats.hlist._
import dev.profunktor.redis4cats.transactions.RedisTransaction

import fpms.repository.AddedPackageIdQueue

class AddedPackageIdRedisQueue[F[_]](conf: RedisConfig)(
    implicit F: ConcurrentEffect[F],
    cs: ContextShift[F],
    timer: Timer[F]
) extends AddedPackageIdQueue[F]
    with LazyLogging
    with RedisLog[F] {
  protected val AforLog = implicitly

  def push(id: Int): F[Unit] =
    RedisResource.resource(conf).use { cmd => cmd.lPush(key, id.toString()).map(_ => ()) }

  def popAll(): F[Seq[Int]] =
    RedisResource.resource(conf).use { cmd =>
      {
        val tx = RedisTransaction(cmd)
        val commands = cmd.lRange(key, 0, -1) :: cmd.del(key) :: HNil
        tx.filterExec(commands).map {
          case res1 ~: _ ~: HNil => res1.map(v => v.toInt)
        }
      }
    }

  private val key = "added_ids"
}
