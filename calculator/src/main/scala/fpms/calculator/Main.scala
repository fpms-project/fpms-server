package fpms.calculator

import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.IOApp
import cats.effect.concurrent.MVar
import com.typesafe.config._
import doobie._
import scopt.OptionParser

import fpms.repository.db.LibraryPackageSqlRepository
import fpms.repository.redis.AddedPackageIdRedisQueue
import fpms.repository.redis.LDILRedisRepository
import fpms.repository.redis.RDSRedisRepository
import fpms.repository.redis.RedisConf

import fpms.calculator.json.JsonLoader
import fpms.calculator.ldil.LDILMapCalculatorWithRedis
import fpms.calculator.rds.RDSMapCalculatorOnMemory
import fpms.calculator.util.PackageSaver

object FpmsCalculator extends IOApp {
  case class ArgOptionConfig(
      mode: String = "run",
      convert: Boolean = false
  )

  val parser = new OptionParser[ArgOptionConfig]("fmpsn") {
    head("fpmsn", "0.1.0")
    help("help").text("prints this usage text")
    cmd("init").action((_, c) => c.copy(mode = "init")).text("initalize data and run server")
    cmd("prepare")
      .action((_, c) => c.copy(mode = "prepare"))
      .text("prepare json and sqls")
      .children(opt[Unit]("convert").action((_, c) => c.copy(convert = true)).text("numberd json files"))
  }

  def run(args: List[String]): IO[ExitCode] = {
    parser.parse(args, ArgOptionConfig()) match {
      case Some(arg) => {
        val config = ConfigFactory.load("app.conf")
        val repo = new LibraryPackageSqlRepository[IO](
          Transactor.fromDriverManager[IO](
            config.getString("server.postgresql.driver"),
            config.getString("server.postgresql.url"),
            config.getString("server.postgresql.user"),
            config.getString("server.postgresql.pass")
          )
        )
        if (arg.mode == "prepare") {
          println("-> prepare data")
          if (arg.convert) {
            println("--> convert json")
            JsonLoader.convertJson()
          }
          println("--> save data to sql(it takes more than one hour)")
          PackageSaver.saveJson(JsonLoader.loadIdList().toList, repo)
          IO.unit.as(ExitCode.Success)
        } else {
          val conf = RedisConf(config.getConfig("server.redis"))
          for {
            m <- MVar.empty[IO, Map[Int, Seq[Int]]]
            lc = new LDILRedisRepository[IO](conf)
            lmc = new LDILMapCalculatorWithRedis[IO](repo, lc, m)
            rmc = new RDSMapCalculatorOnMemory[IO]()
            rc = new RDSRedisRepository[IO](conf)
            aq = new AddedPackageIdRedisQueue[IO](conf)
            calcurator = new RedisDependencyCalculator(repo, lmc, lc, rmc, rc, aq)

            _ <- if (arg.mode == "init") calcurator.initialize() else IO.pure(())
            _ <- calcurator.loop()
          } yield ExitCode.Success
        }
      }
      case None => {
        println(s"error parsing arguments: ${args}")
        IO.unit.as(ExitCode.Error)
      }
    }
  }
}
