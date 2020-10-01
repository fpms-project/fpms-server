package fpms

import org.http4s.HttpApp
import io.circe.generic.auto._
import io.circe.syntax._
import io.circe._, io.circe.generic.semiauto._
import org.http4s.circe._
import org.http4s.dsl._
import org.http4s.implicits._
import org.http4s.HttpRoutes
import cats.implicits._
import fpms.repository.SourcePackageRepository
import cats.effect.ConcurrentEffect
import cats.Applicative
import com.github.sh4869.semver_parser.Range
import scala.util.Try
import org.http4s.Response
import org.http4s.Status
import com.github.sh4869.semver_parser.SemVer
import org.slf4j.LoggerFactory

class ServerApp[F[_]](repo: SourcePackageRepository[F], calcurator: DependencyCalculator)(
    implicit F: ConcurrentEffect[F]
) {
  object dsl extends Http4sDsl[F]
  private val logger = LoggerFactory.getLogger(this.getClass)
  def convertToResponse(
      node: PackageNode
  ): F[PackageNodeRespose] = {
    for {
      src <- repo.findById(node.src)
      directed <- if (node.directed.isEmpty) {
        F.pure(Seq.empty)
      } else {
        repo.findByIds(node.directed.toList.toNel.get)
      }
      set <- if (node.packages.isEmpty) {
        F.pure(Set.empty)
      } else {
        repo.findByIds(node.packages.toList.toNel.get)
      }
    } yield PackageNodeRespose(src.get, directed, set.toSet)
  }

  def getPackages(name: String, range: String): F[Either[String, PackageNodeRespose]] = {
    Try {
      val r = Range(range)
      for {
        packs <- repo.findByName(name)
        x <- {
          if (packs.isEmpty) {
            return F.pure(Left(s"${name} not found"))
          }
          val t = packs.filter(x => r.valid(x.version)).sortWith((a, b) => a.version > b.version).headOption
          val z = t.flatMap(x => calcurator.get(x.id))
          z match {
            case Some(value) => convertToResponse(value).map[Either[String, PackageNodeRespose]](x => Right(x))
            case None        => F.pure[Either[String, PackageNodeRespose]](Left("get failed"))
          }
        }
      } yield x
    }.getOrElse(F.pure[Either[String, PackageNodeRespose]](Left("range error")))
  }

  def ServerApp(): HttpApp[F] = {
    import dsl._
    import fpms.SourcePackage._
    implicit val decoder = jsonEncoderOf[F, PackageNodeRespose]
    implicit val addDecoder = deriveDecoder[AddPackage]
    implicit val decoderxx = jsonOf[F, AddPackage]
    HttpRoutes
      .of[F] {
        case GET -> Root / "hello" / name =>
          F.pure(Response(Status.Ok))
        case GET -> Root / "id" / IntVar(id) =>
          calcurator.get(id) match {
            case Some(value) => Ok(convertToResponse(value))
            case None        => NotFound()
          }
        case GET -> Root / "get" / name / range =>
          getPackages(name, range).flatMap(_ match {
            case Right(v) => Ok(v)
            case Left(v)  => NotFound(v)
          })
        case req @ POST -> Root / "add" => {
          for { 
            _ <- F.pure(logger.info(s"${req.headers}"))
            v <- req.as[AddPackage]
            x <- Ok(calcurator.add(v))
          } yield x
        }
      }
      .orNotFound
  }
}
