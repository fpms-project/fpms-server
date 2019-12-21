package package_manager_server

import cats.effect.Console.io._
import cats.effect.IOApp
import cats.effect._
import cats.implicits._
import cats.effect.concurrent.MVar
import com.gilt.gfc.semver.SemVer
import fs2.concurrent.Topic
import package_manager_server.VersionCondition._
import scala.concurrent.duration._

object Main extends IOApp {
  override def run(arg: List[String]) = for {
    manager <- for {
      mvar <- MVar.of[IO, Map[String, PackageUpdateSubscriber[IO]]](Map.empty)
      topic <- for {
        mvar2 <- MVar.of[IO, Map[String, Topic[IO, PackageUpdateEvent]]](Map.empty)
      } yield new TopicManager[IO](mvar2)
    } yield new PackageUpdateSubscriberManager[IO](mvar, topic)
    _ <- manager.addNewPackage(PackageInfo("A", SemVer("1.0.0"), Map.empty))
    _ <- manager.addNewPackage(PackageInfo("B", SemVer("1.0.0"), Map.empty))
    _ <- manager.addNewPackage(PackageInfo("C", SemVer("1.0.0"), Map.empty))

    _ <- manager.addNewPackage(PackageInfo("D", SemVer("1.0.0"), Map("A" -> "*")))
    _ <- printDep("D", SemVer("1.0.0"), manager)

    _ <- manager.addNewPackage(PackageInfo("A", SemVer("2.0.0"), Map.empty))
    _ <- IO.sleep(1.seconds)
    _ <- printDep("D", SemVer("1.0.0"), manager)

    _ <- manager.addNewPackage(PackageInfo("X", SemVer("1.0.0"), Map("D" -> "*")))
    _ <- IO.sleep(1.seconds)
    _ <- printDep("X", SemVer("1.0.0"), manager)

    _ <- manager.addNewPackage(PackageInfo("A", SemVer("3.0.0"), Map.empty))
    _ <- IO.sleep(1.seconds)
    _ <- printDep("D", SemVer("1.0.0"), manager)
    _ <- printDep("X", SemVer("1.0.0"), manager)

    _ <- manager.addNewPackage(PackageInfo("Y", SemVer("1.0.0"), Map("X" -> "*")))
    _ <- IO.sleep(1.seconds)
    _ <- printDep("Y", SemVer("1.0.0"), manager)

    _ <- manager.addNewPackage(PackageInfo("A", SemVer("4.0.0"), Map.empty))
    _ <- IO.sleep(1.seconds)
    _ <- printDep("D", SemVer("1.0.0"), manager)
    _ <- printDep("X", SemVer("1.0.0"), manager)
    _ <- printDep("Y", SemVer("1.0.0"), manager)

    _ <- manager.addNewPackage(PackageInfo("X", SemVer("2.0.0"), Map.empty))
    _ <- IO.sleep(1.seconds)
    _ <- printDep("Y", SemVer("1.0.0"), manager)


  } yield ExitCode.Success


  def printDep(name: String, version: SemVer, packageUpdateSubscriberManager: PackageUpdateSubscriberManager[IO]) =
    packageUpdateSubscriberManager.getDependencies(name, version).flatMap(
      x => putStrLn(s"${name}@${version.original} -> ${x.map(_.map(d => s"${d.name}@${d.version.original}").mkString(",")).getOrElse("")}")
    )
}
