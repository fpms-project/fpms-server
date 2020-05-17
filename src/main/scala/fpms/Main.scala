package fpms

import cats.effect.concurrent.MVar
import cats.effect.{IOApp, _}
import fpms.repository.memory.{PackageAllDepMemoryRepository, PackageDepRelationMemoryRepository}
import fpms.repository.memoryexception.PackageInfoMemoryRepository
import fpms.util.JsonLoader
import org.slf4j.LoggerFactory

object Main extends IOApp {
  private val logger = LoggerFactory.getLogger(this.getClass)
  override def run(args: List[String]): IO[ExitCode] = {
    logger.info("start log!")
    val packs = JsonLoader.createLists()
    logger.info("json loaded!")
    for {
      repos <- getRepositories()
      _ <- new PackageRegisterer[IO](repos._1, repos._2, repos._3, packs).registerPackages()
    } yield ExitCode.Success
  }

  def getRepositories(): IO[
    (
        PackageInfoMemoryRepository[IO],
        PackageDepRelationMemoryRepository[IO],
        PackageAllDepMemoryRepository[IO]
    )
  ] = {
    for {
      c <- for {
        c <- MVar.of[IO, Map[String, Seq[String]]](Map.empty)
        d <- MVar.of[IO, Map[PackageInfoBase, PackageInfo]](Map.empty)
      } yield new PackageInfoMemoryRepository[IO](c, d)
      a <- for {
        a <- MVar.of[IO, Map[PackageInfoBase, Seq[PackageInfoBase]]](Map.empty)
      } yield new PackageAllDepMemoryRepository[IO](a)
      b <- for {
        b <- MVar.of[IO, Map[String, Seq[PackageInfoBase]]](Map.empty)
      } yield new PackageDepRelationMemoryRepository[IO](b)
    } yield (c, b, a)
  }
}
