package fpms.server

import cats.data.EitherT
import cats.effect.ConcurrentEffect
import cats.implicits._
import com.github.sh4869.semver_parser.Range
import com.typesafe.scalalogging.LazyLogging
import io.circe.generic.auto._
import org.http4s.HttpApp
import org.http4s.HttpRoutes
import org.http4s.circe._
import org.http4s.dsl._
import org.http4s.implicits._

import fpms.LibraryPackage
import fpms.repository.LibraryPackageRepository
import fpms.repository.RDSRepository

class ServerApp[F[_]](packageRepo: LibraryPackageRepository[F], rdsRepository: RDSRepository[F])(
    implicit F: ConcurrentEffect[F]
) extends LazyLogging {
  object dsl extends Http4sDsl[F]

  def convertToResponse(target: Int, allSet: Set[Int]): F[PackageNodeRespose] =
    for {
      src <- packageRepo.findOne(target)
      set <- packageRepo.findByIds(allSet.toList)
    } yield PackageNodeRespose(src.get, set.toSet)

  def getPackages(name: String, range: String): F[Either[String, PackageNodeRespose]] = {
    logger.info(s"start get package from redis: ${name}@${range}")
    (for {
      // Rangeのパース
      range <- EitherT.fromOption[F](Range.parse(range), "cannot parse the range")
      // name パッケージを探す
      packs <- EitherT(
        packageRepo.findByName(name).map {
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
          v <- rdsRepository.get(target.id)
          x <- convertToResponse(target.id, v.getOrElse(Set.empty)).map(Some(_))
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
    HttpRoutes
      .of[F] {
        case GET -> Root / "get_package" / name =>
          for {
            list <- packageRepo.findByName(name)
            x <- Ok(list)
          } yield x
        case GET -> Root / "id" / IntVar(id) =>
          for {
            v <- rdsRepository.get(id)
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
      }
      .orNotFound
  }
}

case class PackageNodeRespose(
    target: LibraryPackage,
    packages: Set[LibraryPackage]
)