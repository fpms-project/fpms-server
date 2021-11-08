package fpms.calculator

import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.IOApp
import com.typesafe.config.*
import scopt.OptionParser

import fpms.repository.db.LibraryPackageSqlRepository
import fpms.repository.redis.AddedPackageIdRedisQueue
import fpms.repository.redis.LDILRedisRepository
import fpms.repository.redis.RDSRedisRepository
import fpms.repository.redis.RedisConfig
import fpms.repository.db.PostgresConfig

import fpms.calculator.json.JsonLoader
import fpms.calculator.ldil.LDILMapCalculatorWithRedis
import fpms.calculator.util.PackageSaver
import fpms.calculator.rds.RoundRobinRDSMapCalculator
import cats.effect.unsafe.IORuntime
import cats.effect.std.Queue

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
        if (arg.mode == "prepare") {
          prepare(arg.convert)
        } else {
          run(arg.mode == "init")
        }
      }
      case None => {
        println(s"error parsing arguments: ${args}")
        IO.unit.as(ExitCode.Error)
      }
    }
  }

  private def run(init: Boolean): IO[ExitCode] = {
    val conf = RedisConfig(config.getConfig("server.redis"))
    for {
      m <- Queue.bounded[IO, Map[Int, Seq[Int]]](1)
      lc = new LDILRedisRepository[IO](conf)
      lmc = new LDILMapCalculatorWithRedis[IO](repo, lc, m)
      rmc = new RoundRobinRDSMapCalculator[IO]
      rc = new RDSRedisRepository[IO](conf)
      aq = new AddedPackageIdRedisQueue[IO](conf)
      calcurator = new DependencyCalculator(repo, lmc, lc, rmc, rc, aq)
      _ <- if (init) calcurator.initialize() else IO.pure(())
      _ <- calcurator.loop()
    } yield ExitCode.Success
  }

  private def prepare(convert: Boolean): IO[ExitCode] = {
    println("-> prepare data")
    if (convert) {
      println("--> convert json")
      JsonLoader.convertJson()
    }
    println("--> save data to sql(it takes more than one hour)")
    PackageSaver.saveJson(JsonLoader.loadIdList().toList, repo)(IORuntime.global)
    IO.unit.as(ExitCode.Success)
  }

  private lazy val config = ConfigFactory.load("app.conf")
  private lazy val repo = new LibraryPackageSqlRepository[IO](
    PostgresConfig(config.getConfig("server.postgresql"))
  )
}
