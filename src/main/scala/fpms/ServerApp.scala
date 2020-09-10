package fpms

import org.http4s.HttpApp
import io.circe.generic.auto._
import io.circe.syntax._
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

class ServerApp[F[_]](repo: SourcePackageRepository[F], map: Map[Int, PackageNode])(implicit F: ConcurrentEffect[F]) {
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

  def getPackages(name: String, range: String) = {
    Try {
      val r = Range(range)
      for {
        packs <- repo.findByName(name)
        x <- {
          val t = packs
            .filter(x => r.valid(SemVer(x.version)))
            .sortWith((a, b) => SemVer(a.version) > SemVer(b.version))
            .headOption
          val z = t.flatMap(x => map.get(x.id))
          z match {
            case Some(value) => convertToResponse(value).map[Option[PackageNodeRespose]](x => Some(x))
            case None        => F.pure[Option[PackageNodeRespose]](None)
          }
        }
      } yield x
    }.getOrElse(F.pure[Option[PackageNodeRespose]](None))
  }

  def ServerApp(): HttpApp[F] = {
    import dsl._
    implicit val decoder = jsonEncoderOf[F, PackageNodeRespose]
    HttpRoutes
      .of[F] {
        case GET -> Root / "hello" / name =>
          F.pure(Response(Status.Ok))
        case GET -> Root / "id" / IntVar(id) =>
          map.get(id) match {
            case Some(value) => Ok(convertToResponse(value))
            case None        => NotFound()
          }
        case GET -> Root / "get" / name / range =>
          getPackages(name, range).flatMap(_ match {
            case Some(v) => Ok(v)
            case None    => NotFound()
          })
      }
      .orNotFound
  }
}

case class PackageNodeRespose(
    src: SourcePackage,
    directed: Seq[SourcePackage],
    packages: Set[SourcePackage]
)
