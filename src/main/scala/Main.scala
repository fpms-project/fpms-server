package package_manager_server

import cats.data.EitherT
import cats.effect.Console.io._
import cats.effect.IOApp
import cats.effect._
import cats.effect.concurrent.MVar
import cats.syntax.all._
import com.gilt.gfc.semver.SemVer
import fs2.concurrent.Topic
import io.circe.generic.auto._
import org.http4s.HttpRoutes
import org.http4s.circe._
import org.http4s.dsl.io._
import org.http4s.implicits._
import package_manager_server.VersionCondition._
import scala.concurrent.duration._

object Main extends IOApp {
  override def run(arg: List[String]) = core.value.map {
    case Right(v) => ()
    case Left(e) => println(s"Error :$e")
  }.as(ExitCode.Success)


  def core = for {
    manager <- createPackageUpdateSubscriberManager
    _ <- manager.addNewPackage(PackageInfo("A", SemVer("1.0.0"), Map.empty))

    _ <- manager.addNewPackage(PackageInfo("B", SemVer("1.0.0"), Map.empty))
    _ <- manager.addNewPackage(PackageInfo("D", SemVer("1.0.0"), Map("A" -> "^1.0.0")))
    _ <- printDep("D", "1.0.0", manager)
    _ <- manager.addNewPackage(PackageInfo("A", SemVer("1.0.4"), Map.empty))
    _ <- sleep(1.seconds)
    _ <- printDep("D", "1.0.0", manager)
    _ <- sleep(10.seconds)
  } yield ExitCode.Success


  def printDep(name: String, version: VersionCondition, packageUpdateSubscriberManager: PackageUpdateSubscriberManager[IO]): EitherT[IO, Any, Unit] =
    packageUpdateSubscriberManager.getDependencies(name, version).flatMap(
      x => EitherT.right[Any](putStrLn(s"Deps: $name@$version -> ${x.map(d => s"${d.name}@${d.version.original}").mkString(",")}"))
    )

  def sleep(duration: FiniteDuration): EitherT[IO, Any, Unit] = EitherT.right(IO.sleep(duration))

  def createPackageUpdateSubscriberManager: EitherT[IO, Any, PackageUpdateSubscriberManager[IO]] =
    EitherT[IO, Any, PackageUpdateSubscriberManager[IO]]({
      for {
        mvar <- MVar.of[IO, Map[String, PackageUpdateSubscriber[IO]]](Map.empty)
        topic <- for {
          mvar2 <- MVar.of[IO, Map[String, Topic[IO, PackageUpdateEvent]]](Map.empty)
        } yield new TopicManager[IO](mvar2)
      } yield Right(new PackageUpdateSubscriberManager[IO](mvar, topic))
    })
}



object Server {
  case class PackageInfo(version: String, name: String)

  implicit val decoder = jsonOf[IO, PackageInfo]
  def server(manager: PackageUpdateSubscriberManager[IO]) = HttpRoutes.of[IO] {
    case req @ GET -> Root / "getDeps" =>
      for {
        info <- req.as[PackageInfo]
        les <- manager.getDependencies(info.name,info.version).value
        resp <- Ok(())
      } yield resp

  }.orNotFound
}
