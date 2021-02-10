package fpms.server

import cats.data.EitherT
import cats.effect.ConcurrentEffect
import cats.implicits._
import com.github.sh4869.semver_parser.Range
import com.typesafe.scalalogging.LazyLogging
import io.circe.generic.auto._
import io.circe.generic.semiauto._
import org.http4s.HttpApp
import org.http4s.HttpRoutes
import org.http4s.circe._
import org.http4s.dsl._
import org.http4s.implicits._

import fpms.LibraryPackage
import fpms.server.calcurator.AddPackage
import fpms.server.calcurator.DependencyCalculator
import fpms.server.calcurator.PackageCalcuratedDeps
import fpms.server.repository.LibraryPackageRepository

class ServerApp[F[_]](repo: LibraryPackageRepository[F], calcurator: DependencyCalculator[F])(
    implicit F: ConcurrentEffect[F]
) extends LazyLogging {
  object dsl extends Http4sDsl[F]

  def convertToResponse(target: Int, node: PackageCalcuratedDeps): F[PackageNodeRespose] =
    for {
      src <- repo.findOne(target)
      directed <- repo.findByIds(node.direct.toList)
      set <- repo.findByIds(node.all.toList)
    } yield PackageNodeRespose(src.get, directed, set.toSet)

  def getPackages(name: String, range: String): F[Either[String, PackageNodeRespose]] = {
    logger.info(s"start get package from redis: ${name}@${range}")
    (for {
      // Rangeのパース
      range <- EitherT.fromOption[F](Range.parse(range), "cannot parse the range")
      // name パッケージを探す
      packs <- EitherT(
        repo.findByName(name).map {
          _ match {
            case head :: tl => Right(head :: tl)
            case Nil        => Left(s"package ${name} is not found")
          }
        }
      )
      // バージョンを満たすパッケージを探す
      target <- EitherT.fromOption[F](
        packs.filter(x => range.valid(x.version)).sortWith((a, b) => a.version > b.version).headOption,
        "No packages were found that met the version requirements"
      )
      // 計算結果とレスポンスの取得
      res <- EitherT.fromOptionF[F, String, PackageNodeRespose](
        for {
          v <- calcurator.get(target.id)
          x <- if (v.isEmpty) {
            F.pure[Option[PackageNodeRespose]](None)
          } else {
            convertToResponse(target.id, v.get).map(Some(_))
          }
        } yield x,
        "calcuration not completed"
      )
    } yield res).value
  }

  def ServerApp(): HttpApp[F] = {
    import dsl._
    implicit val libraryEncoder = LibraryPackage.encoder
    implicit val decoder = jsonEncoderOf[F, PackageNodeRespose]
    implicit val encoder = jsonEncoderOf[F, List[LibraryPackage]]
    implicit val addDecoder = deriveDecoder[AddPackage]
    implicit val decoderxx = jsonOf[F, AddPackage]
    HttpRoutes
      .of[F] {
        case GET -> Root / "get_package" / name =>
          for {
            list <- repo.findByName(name)
            x <- Ok(list)
          } yield x
        case GET -> Root / "id" / IntVar(id) =>
          for {
            v <- calcurator.get(id)
            x <- v match {
              case Some(value) => Ok(convertToResponse(id, value))
              case None        => NotFound()
            }
          } yield x
        case GET -> Root / "get" / name / range =>
          getPackages(name, range).flatMap(_ match {
            case Right(v) => Ok(v)
            case Left(v)  => NotFound(v)
          })
        case req @ POST -> Root / "add" => {
          for {
            v <- req.as[AddPackage]
            x <- Ok(calcurator.add(v))
          } yield x
        }
      }
      .orNotFound
  }
}

case class PackageNodeRespose(
    target: LibraryPackage,
    directed: Seq[LibraryPackage],
    packages: Set[LibraryPackage]
)
