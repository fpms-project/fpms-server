package fpms.server

import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.IOApp
import com.typesafe.config._
import doobie._
import org.http4s.server.blaze.BlazeServerBuilder

import fpms.repository.db.LibraryPackageSqlRepository
import fpms.repository.redis.RDSRedisRepository
import fpms.repository.redis.RedisConf

object FpmsServer extends IOApp {

  def run(args: List[String]): IO[ExitCode] = {
    val config = ConfigFactory.load("app.conf")
    val repo = new LibraryPackageSqlRepository[IO](
      Transactor.fromDriverManager[IO](
        config.getString("server.postgresql.driver"),
        config.getString("server.postgresql.url"),
        config.getString("server.postgresql.user"),
        config.getString("server.postgresql.pass")
      )
    )
    val conf = RedisConf(config.getConfig("server.redis"))
    val rc = new RDSRedisRepository[IO](conf)
    BlazeServerBuilder[IO]
      .bindHttp(8080, "0.0.0.0")
      .withHttpApp(new ServerApp[IO](repo, rc).ServerApp())
      .serve
      .compile
      .drain
      .as(ExitCode.Success)
  }
}
