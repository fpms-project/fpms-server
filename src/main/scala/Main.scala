package package_manager_server

import cats.effect._
import cats.implicits._
import cats.effect.{ContextShift, Effect, IOApp, Timer}
import org.http4s.HttpApp
import org.http4s.server.{Router, Server}
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.syntax.kleisli._

object Main extends IOApp {
  def run(arg: List[String]) =
    ExampleApp.resource[IO].use(_ => IO.never).as(ExitCode.Success)
}

object ExampleApp {
  def httpApp[F[_]: Effect : ContextShift : Timer](blocker: Blocker): HttpApp[F] =
    Router().orNotFound

  def resource[F[_]: ConcurrentEffect: ContextShift: Timer]: Resource[F, Server[F]] =
    for {
      blocker <- Blocker[F]
      app = httpApp[F](blocker)
      server <- BlazeServerBuilder[F]
        .bindHttp(8080)
        .withHttpApp(app)
        .resource
    } yield server
}
