package package_manager_server

import cats.data.EitherT
import cats.effect.Console.io._
import cats.effect.IOApp
import cats.effect._
import cats.effect.concurrent.MVar
import cats.syntax.all._
import com.gilt.gfc.semver.SemVer
import fs2.concurrent.Topic
import package_manager_server.VersionCondition._
import scala.concurrent.duration._

object Main extends IOApp {
  override def run(arg: List[String]) = core.value.as(ExitCode.Success)


  def core = for {
    manager <- EitherT[IO, Any, PackageUpdateSubscriberManager[IO]]({
      val x = for {
        mvar <- MVar.of[IO, Map[String, PackageUpdateSubscriber[IO]]](Map.empty)
        topic <- for {
          mvar2 <- MVar.of[IO, Map[String, Topic[IO, PackageUpdateEvent]]](Map.empty)
        } yield new TopicManager[IO](mvar2)
      } yield Right(new PackageUpdateSubscriberManager[IO](mvar, topic))
      x
    })
    _ <- manager.addNewPackage(PackageInfo("A", SemVer("1.0.0"), Map.empty))
    _ <- sleep(1.seconds)
    _ <- (0 to 1000 toList).map(i => PackageInfo(i.toString, SemVer("1.0.0"), Map("A" -> "*"))).map(p => manager.addNewPackage(p)).toNel.map(l => EitherT.right(l.map(_.value).parSequence_)).getOrElse(EitherT.rightT[IO, Unit](()))
    _ <- manager.addNewPackage(PackageInfo("D", SemVer("1.0.0"), Map("A" -> "*")))
    _ <- printDep("D", SemVer("1.0.0"), manager)
    _ <- manager.addNewPackage(PackageInfo("A", SemVer("2.0.0"), Map.empty))
    _ <- sleep(1.seconds)
    _ <- printDep("D", SemVer("1.0.0"), manager)
    _ <- (0 to 1000 toList).map(_.toString).map(e => printDep(e, SemVer("1.0.0"), manager)).toNel.map(l => EitherT.right(l.map(_.value).parSequence_)).get
    _ <- sleep(10.seconds)
  } yield ExitCode.Success


  def printDep(name: String, version: SemVer, packageUpdateSubscriberManager: PackageUpdateSubscriberManager[IO]): EitherT[IO, Any, Unit] =
    packageUpdateSubscriberManager.getDependencies(name, version).flatMap(
      x => EitherT.right[Any](putStrLn(s"Deps: ${name}@${version.original} -> ${x.map(d => s"${d.name}@${d.version.original}").mkString(",")}"))
    )

  def sleep(duration: FiniteDuration): EitherT[IO, Any, Unit] = EitherT.right(IO.sleep(duration))
}
