package fpms

import fpms.json.JsonLoader
import org.slf4j.LoggerFactory
import cats.effect.{IO, IOApp, ExitCode}
import org.http4s.server.blaze.BlazeServerBuilder
import cats.implicits._
import com.typesafe.config._
import doobie._
import fpms.repository.SourcePackageRepository
import fpms.repository.db.SourcePackageSqlRepository
import com.redis.RedisClient

object Fpms extends IOApp {
  private val logger = LoggerFactory.getLogger(this.getClass)

  def saveToDb(xa: Transactor[IO]) {
    val packs = JsonLoader.loadIdList()
    util.SqlSaver.saveJson(packs, xa)
  }

  def run(args: List[String]): IO[ExitCode] = {
    val config = ConfigFactory.load("app.conf").getConfig("server.postgresql")
    val xa = Transactor.fromDriverManager[IO](
      config.getString("driver"),
      config.getString("url"),
      config.getString("user"),
      config.getString("pass")
    )
    val repo = new SourcePackageSqlRepository[IO](xa)
    val command = args.headOption.getOrElse("server")
    if (command == "db") {
      saveToDb(xa)
      IO.unit.as(ExitCode.Success)
    } else if (command == "convert_json") {
      JsonLoader.convertJson()
      IO.unit.as(ExitCode.Success)
    } else {
      val r = new RedisClient("localhost", 6379)
      logger.info("setup")
      val calcurator = new LocalDependencyCalculator()
      calcurator.initialize()
      /*
      val calcurator = new RedisDependecyCalculator[IO](r, repo)
      // calcurator.initialize() 
      */
      val app = new ServerApp[IO](repo, calcurator)
      BlazeServerBuilder[IO]
        .bindHttp(8080, "0.0.0.0")
        .withHttpApp(app.ServerApp())
        .serve
        .compile
        .drain
        .as(ExitCode.Success)
    }
  }
}
