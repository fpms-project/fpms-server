package fpms

import cats.data.EitherT
import cats.effect.IOApp
import cats.effect._
import cats.effect.concurrent.MVar
import fpms.VersionCondition._
import fs2.concurrent.Topic
import org.http4s.HttpRoutes
import org.http4s.circe._
import org.http4s.dsl.io._
import org.http4s.implicits._
import cats.implicits._

object Main extends IOApp {

  import org.http4s.server.blaze._

  override def run(arg: List[String]) = {
    val topicManager = createTopicManager.value.unsafeRunSync().right.get
    val manager = createPackageUpdateSubscriberManager(topicManager).value.unsafeRunSync().right.get
    val jsonLoader = new JsonLoader(topicManager,manager)
    jsonLoader.initialize(1)
    BlazeServerBuilder[IO].bindHttp(8080).withHttpApp(Server.server(manager)).serve.compile.drain.as(ExitCode.Success)
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
  import io.circe.syntax._
  import org.http4s.circe.CirceEntityDecoder._

  def server(manager: PackageUpdateSubscriberManager[IO]) = HttpRoutes.of[IO] {
    case GET -> Root / "get_deps" / name / version =>
      for {
        les <- manager.getDependencies(name, version).value
        resp <- Ok(les.asJson)
      } yield resp
    case GET -> Root / "counts" =>
      for {
        les <- manager.countPackageNames().value
        resp <- Ok(les.asJson)
      } yield resp
    case req@POST -> Root / "add_package" =>
      for {
        pack <- req.as[PackageInfo]
        _ <- manager.addNewPackage(pack).value
        resp <- Ok(())
      } yield resp
  }.orNotFound
}
