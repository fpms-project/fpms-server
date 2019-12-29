package fpms

import cats.data.EitherT
import cats.effect.IOApp
import cats.effect._
import cats.effect.concurrent.MVar
import cats.syntax.all._
import VersionCondition._
import fs2.concurrent.Topic
import org.http4s.HttpRoutes
import org.http4s.circe._
import org.http4s.dsl.io._
import org.http4s.implicits._

object Main extends IOApp {

  import org.http4s.server.blaze._

  override def run(arg: List[String]) =
    createPackageUpdateSubscriberManager
      .map(manager =>
        BlazeServerBuilder[IO].bindHttp(8080).withHttpApp(Server.server(manager))
      )
      .value
      .flatMap {
        case Right(v) => v.serve.compile.drain
        case Left(v) => IO {
          println(v)
        }
      }.as(ExitCode.Success)

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
    case req@POST -> Root / "add_package" =>
      for {
        pack <- req.as[PackageInfo]
        _ <- manager.addNewPackage(pack).value
        resp <- Ok(())
      } yield resp
  }.orNotFound
}