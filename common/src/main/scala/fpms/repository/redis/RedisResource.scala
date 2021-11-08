package fpms.repository.redis

import dev.profunktor.redis4cats.Redis
import cats.effect.Concurrent
import dev.profunktor.redis4cats.effect.Log
import cats.effect.Resource
import dev.profunktor.redis4cats.RedisCommands
import cats.effect.kernel.Async

object RedisResource {
  def resource[F[_]](
      conf: RedisConfig
  )(implicit F: Async[F], log: Log[F]): Resource[F, RedisCommands[F, String, String]] =
    Redis[F].utf8(s"redis://${conf.host}:${conf.port}")
}
