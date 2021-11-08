package fpms.server

import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.IOApp
import com.typesafe.config.*
import org.http4s.blaze.server.BlazeServerBuilder

import fpms.repository.db.LibraryPackageSqlRepository
import fpms.repository.redis.RDSRedisRepository
import fpms.repository.redis.RedisConfig
import fpms.repository.redis.AddedPackageIdRedisQueue
import fpms.repository.db.PostgresConfig

object FpmsServer extends IOApp {

  def run(args: List[String]): IO[ExitCode] = {
    val config = ConfigFactory.load("app.conf")
    val repo = new LibraryPackageSqlRepository[IO](
      PostgresConfig(config.getConfig("server.postgresql"))
    )
    val conf = RedisConfig(config.getConfig("server.redis"))
    val rc = new RDSRedisRepository[IO](conf)
    val aq = new AddedPackageIdRedisQueue[IO](conf)
    BlazeServerBuilder[IO]
      .bindHttp(8080, "0.0.0.0")
      .withHttpApp(new ServerApp[IO](repo, rc, aq).app())
      .serve
      .compile
      .drain
      .as(ExitCode.Success)
  }
}
