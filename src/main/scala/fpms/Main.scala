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
import scopt.OptionParser

object Fpms extends IOApp {

  case class ArgOptionConfig(
      mode: String = "run",
      calcurator: String = "redis",
      prepare: Boolean = false
  )

  private val logger = LoggerFactory.getLogger(this.getClass)
  val parser = new OptionParser[ArgOptionConfig]("fmpsn") {
    head("fpmsn", "0.1.0")
    opt[Unit]("in-memory")
      .text("using local calcurator instead of redis (always run with initalize)")
      .action((_, c) => c.copy(calcurator = "memory"))
    help("help").text("prints this usage text")
    cmd("init")
      .action((m, c) => c.copy(mode = "init"))
      .text("initalize data and run server")
      .children(
        opt[Unit]("prepare")
          .action((_, c) => c.copy(prepare = true))
          .text("convert json and save package data into sql before initialize server")
      )
  }

  def run(args: List[String]): IO[ExitCode] = {
    parser.parse(args, ArgOptionConfig()) match {
      case Some(arg) => {
        val config = ConfigFactory.load("app.conf")
        val repo = new SourcePackageSqlRepository[IO](
          Transactor.fromDriverManager[IO](
            config.getString("server.postgresql.driver"),
            config.getString("server.postgresql.url"),
            config.getString("server.postgresql.user"),
            config.getString("server.postgresql.pass")
          )
        )
        val calcurator =
          if (arg.calcurator == "memory") new LocalDependencyCalculator()
          else {
            val r = new RedisClient("localhost", 6379)
            new RedisDependecyCalculator(r, repo)
          }
        if (arg.mode == "init") {
          if (arg.prepare) {
            println("-> prepare data")
            println("--> convert json")
            JsonLoader.convertJson()
            println("--> save data to sql(it takes more than one hour)")
            util.SqlSaver.saveJson(JsonLoader.loadIdList(), repo)
          }
          println("-> initalize data")
          calcurator.initialize()
        }
        println("-> start server")
        val app = new ServerApp[IO](repo, calcurator)
        BlazeServerBuilder[IO]
          .bindHttp(8080, "0.0.0.0")
          .withHttpApp(app.ServerApp())
          .serve
          .compile
          .drain
          .as(ExitCode.Success)
      }
      case _ =>
        println("error: failed parse to arguments")
        IO.unit.as(ExitCode.Error)
    }
  }
}
