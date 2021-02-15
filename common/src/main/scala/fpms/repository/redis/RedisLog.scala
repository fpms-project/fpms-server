package fpms.repository.redis

import com.typesafe.scalalogging.LazyLogging
import dev.profunktor.redis4cats.effect.Log
import cats.Applicative

trait RedisLog[F[_]] extends LazyLogging {
  protected val AforLog: Applicative[F]
  implicit val log: Log[F] = new Log[F] {
    def debug(msg: => String): F[Unit] = AforLog.pure(())
    def error(msg: => String): F[Unit] = AforLog.pure(logger.error(msg))
    def info(msg: => String): F[Unit] = AforLog.pure(logger.debug(msg))
  }
}
