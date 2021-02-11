package fpms.repository.redis

import dev.profunktor.redis4cats.Redis
import cats.effect.Concurrent
import cats.effect.ContextShift
import dev.profunktor.redis4cats.effect.Log
import cats.effect.Resource
import dev.profunktor.redis4cats.RedisCommands

object RedisResource {
  def resource[F[_]: Concurrent](
      conf: RedisConf
  )(implicit cs: ContextShift[F], log: Log[F]): Resource[F, RedisCommands[F, String, String]] =
    Redis[F].utf8(s"redis://${conf.host}:${conf.port}")
}
