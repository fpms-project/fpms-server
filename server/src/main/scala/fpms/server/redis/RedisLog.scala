package fpms.server.redis

import com.typesafe.scalalogging.LazyLogging
import dev.profunktor.redis4cats.effect.Log
import cats.Applicative

trait RedisLog[F[_]] extends LazyLogging {
  protected val X: Applicative[F]
  implicit val log: Log[F] = new Log[F] {
    def debug(msg: => String): F[Unit] = X.pure(logger.debug(msg))
    def error(msg: => String): F[Unit] = X.pure(logger.error(msg))
    def info(msg: => String): F[Unit] = X.pure(logger.info(msg))
  }
}
