package fpms.repository.redis

import cats.effect.ConcurrentEffect
import cats.effect.ContextShift
import cats.effect.Timer
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import dev.profunktor.redis4cats.hlist._
import dev.profunktor.redis4cats.transactions.RedisTransaction
import dev.profunktor.redis4cats.RedisCommands

import fpms.repository.AddedPackageIdQueue
import cats.Parallel

class AddedPackageIdRedisQueue[F[_]](conf: RedisConfig)(
    implicit F: ConcurrentEffect[F],
    cs: ContextShift[F],
    timer: Timer[F],
    p: Parallel[F]
) extends AddedPackageIdQueue[F]
    with LazyLogging
    with RedisLog[F] {
  protected val AforLog = implicitly

  def push(id: Int): F[Unit] = resource.use(_push(id))

  def popAll(): F[Seq[Int]] = resource.use(_popAll)

  private def _push(id: Int)(cmd: RedisCommands[F, String, String]): F[Unit] =
    cmd.lPush(key, id.toString()).map(_ => ())

  private def _popAll(cmd: RedisCommands[F, String, String]): F[Seq[Int]] = {
    val tx = RedisTransaction(cmd)
    // 追加と削除を同時に行う
    val commands = cmd.lRange(key, 0, -1) :: cmd.del(key) :: HNil
    tx.filterExec(commands).map {
      case res1 ~: _ ~: HNil => res1.map(v => v.toInt)
    }
  }

  private lazy val resource = RedisResource.resource(conf)

  private val key = "added_ids"
}
