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
    if (args.headOption.exists(_ == "db")) {
      saveToDb(xa)
    }
    logger.info("setup")
    val map = DependencyCalculator.inialize()
    val app = new ServerApp[IO](repo, map)
    BlazeServerBuilder[IO]
      .bindHttp(8080, "localhost")
      .withHttpApp(app.ServerApp())
      .serve
      .compile
      .drain
      .as(ExitCode.Success)
  }
}
