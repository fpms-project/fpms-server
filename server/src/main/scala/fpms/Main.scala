package fpms

import cats.data.EitherT
import cats.effect.IOApp
import cats.effect._
import cats.effect.concurrent.MVar
import fpms.PackageUpdateSubscriberManager.PUSMError
import fpms.VersionCondition._
import fs2.concurrent.Topic
import io.circe.Encoder
import io.circe.Json
import org.http4s.HttpRoutes
import org.http4s.circe._
import org.http4s.dsl.io._
import org.http4s.implicits._
import scala.concurrent.duration._

object Main extends IOApp {

  import org.http4s.server.blaze._

  override def run(arg: List[String]) = {
    val topicManager = createTopicManager.value.unsafeRunSync().right.get
    val manager = createPackageUpdateSubscriberManager(topicManager).value.unsafeRunSync().right.get
    // val jsonLoader = new JsonLoader(topicManager,manager)
    // jsonLoader.initialize()
    for {
      _ <- manager.addNewPackage(PackageInfo("z", "1.0.0", Map.empty[String, String])).value
      _ <- manager.addNewPackage(PackageInfo("y", "1.0.0", Map.empty[String, String])).value
      _ <- manager.addNewPackage(PackageInfo("x", "1.0.0", Map.empty[String, String])).value
      _ <- manager.addNewPackage(PackageInfo("f", "1.0.0", Map.empty[String, String])).value
      _ <- IO.sleep(1.seconds)
      _ <- manager.addNewPackage(PackageInfo("w", "1.0.0", Map("x" -> "^1.0.0"))).value
      _ <- manager.addNewPackage(PackageInfo("w", "1.1.0", Map("y" -> "^1.0.0"))).value
      _ <- manager.addNewPackage(PackageInfo("e", "1.0.0", Map("f" -> "^1.0.0"))).value
      _ <- manager.addNewPackage(PackageInfo("e", "1.1.0", Map("f" -> "^1.0.0"))).value
      _ <- manager.addNewPackage(PackageInfo("d", "1.0.0", Map("w" -> "1.0.0"))).value
      _ <- manager.addNewPackage(PackageInfo("c", "1.0.0", Map("z" -> "^1.0.0"))).value
      _ <- manager.addNewPackage(PackageInfo("b", "1.0.0", Map("y" -> "^1.0.0"))).value
      _ <- manager.addNewPackage(PackageInfo("a", "1.0.0", Map("e" -> "^1.0.0", "w" -> "^1.0.0"))).value
      _ <- IO.sleep(5.seconds)
      _ <- manager.addNewPackage(PackageInfo("f", "1.1.0", Map("x" -> "^1.0.0"))).value
      _ <- BlazeServerBuilder[IO].bindHttp(8080).withHttpApp(Server.server(manager)).serve.compile.drain
    } yield ExitCode.Success
  }


  def createPackageUpdateSubscriberManager(topic: TopicManager[IO]): EitherT[IO, Any, PackageUpdateSubscriberManager[IO]] =
    EitherT[IO, Any, PackageUpdateSubscriberManager[IO]]({
      for {
        mvar <- MVar.of[IO, Map[String, PackageUpdateSubscriber[IO]]](Map.empty)
      } yield Right(new PackageUpdateSubscriberManager[IO](mvar, topic))
    })

  def createTopicManager: EitherT[IO, Any, TopicManager[IO]] =
    EitherT.right[Any](MVar.of[IO, Map[String, Topic[IO, PackageUpdateEvent]]](Map.empty).map(mvar2 => new TopicManager[IO](mvar2)))
}


object Server {

  import PackageInfo._
  import io.circe.generic.auto._
  import org.http4s.circe.CirceEntityDecoder._

  def server(manager: PackageUpdateSubscriberManager[IO]) = HttpRoutes.of[IO] {
    case GET -> Root / "get_deps" / name / condition =>
      for {
        les <- manager.getDependencies(name, condition).value
        resp <- Ok(toJson(les))
      } yield resp
    case GET -> Root / "counts" =>
      for {
        les <- manager.countPackageNames().value
        resp <- Ok(toJson(les))
      } yield resp
    case GET -> Root / "package" / name =>
      for {
        les <- manager.getPackage(name).value
        resp <- Ok(toJson(les))
      } yield resp
    case req@POST -> Root / "add_package" =>
      for {
        pack <- req.as[PackageInfo]
        _ <- manager.addNewPackage(pack).value
        resp <- Ok(())
      } yield resp
  }.orNotFound

  def toJson[V](either: Either[PUSMError, V])(implicit encoder: Encoder[V]): Json =
    either match {
      case Left(v) => Json.obj(("error", Json.fromString(v.toString)))
      case Right(v) => encoder(v)
    }

}
