package fpms

import cats.effect.concurrent.MVar
import cats.effect.concurrent.Semaphore
import fpms.json.JsonLoader
import org.slf4j.LoggerFactory
import cats.effect.{IO, IOApp, ExitCode}
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.HttpRoutes
import cats.implicits._
import com.typesafe.config._
import doobie._
import fpms.repository.SourcePackageRepository
import fpms.repository.db.SourcePackageSqlRepository
import cats.data.NonEmptyList

object Fpms extends IOApp {
  private val logger = LoggerFactory.getLogger(this.getClass)

  def convertToResponse(repo: SourcePackageRepository[IO], node: PackageNode): PackageNodeRespose = {
    val result = for {
      src <- repo.findById(node.src)
      directed <- repo.findByIds(node.directed.toList.toNel.get)
      set <- repo.findByIds(node.packages.toList.toNel.get)
    } yield PackageNodeRespose(src.get, directed, set.toSet)
    result.unsafeRunSync()
  }

  def helloWorldService(map: Map[Int, PackageNode], repo: SourcePackageRepository[IO]) = {
    import io.circe.generic.auto._
    import io.circe.syntax._
    import org.http4s.circe._
    import org.http4s.dsl.io._
    import org.http4s.implicits._
    implicit val userDecoder = jsonEncoderOf[IO, PackageNodeRespose]
    HttpRoutes
      .of[IO] {
        case GET -> Root / "hello" / name =>
          Ok(s"Hello, $name.")
        case GET -> Root / "id" / IntVar(id) =>
          Ok(convertToResponse(repo, map.get(id).get))
      }
      .orNotFound
  }

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
    BlazeServerBuilder[IO]
      .bindHttp(8080, "localhost")
      .withHttpApp(helloWorldService(map, repo))
      .serve
      .compile
      .drain
      .as(ExitCode.Success)
  }
}

case class PackageNodeRespose(
    src: SourcePackage,
    directed: Seq[SourcePackage],
    packages: Set[SourcePackage]
)
