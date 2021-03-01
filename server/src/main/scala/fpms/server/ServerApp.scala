package fpms.server

import cats.data.EitherT
import cats.effect.ConcurrentEffect
import cats.implicits._
import com.github.sh4869.semver_parser.Range
import com.typesafe.scalalogging.LazyLogging
import io.circe.generic.auto._
import org.http4s.HttpApp
import org.http4s.HttpRoutes
import org.http4s.dsl._
import org.http4s.dsl.impl.OptionalQueryParamDecoderMatcher
import org.http4s.implicits._

import fpms.LibraryPackage
import fpms.repository.AddedPackageIdQueue
import fpms.repository.LibraryPackageRepository
import fpms.repository.RDSRepository

class ServerApp[F[_]](
    packageRepo: LibraryPackageRepository[F],
    rdsRepository: RDSRepository[F],
    addedQueue: AddedPackageIdQueue[F]
)(
    implicit F: ConcurrentEffect[F]
) extends LazyLogging {
  object dsl extends Http4sDsl[F]

  def convertToResponse(target: Int, allSet: Set[Int]): F[PackageRDS] =
    // TODO: validation(targetにdepsが指定されていてallSetがzeroだった場合はエラーにする)
    for {
      src <- packageRepo.findOne(target)
      set <- packageRepo.findByIds(allSet.toList)
    } yield PackageRDS(src.get, set.toSet)

  def getPackages(request: PackageRequest): F[Either[String, PackageRDS]] = {
    logger.info(s"start get package from redis: ${request.name}@${request.range}")
    (for {
      // Rangeのパース
      range <- EitherT.fromOption[F](Range.parse(request.range), "cannot parse the range")
      // name パッケージを探す
      packs <- EitherT(
        packageRepo.findByName(request.name).map {
          _ match {
            case head :: tl => Right(head :: tl)
            case Nil        => Left(s"package ${request.name} is not found")
          }
        }
      )
      // バージョンを満たすパッケージを探す
      target <- EitherT.fromOption[F](
        packs.filter(x => range.valid(x.version)).sortWith((a, b) => a.version > b.version).headOption,
        "No packages were found that met the version requirements"
      )
      // 計算結果とレスポンスの取得
      res <- EitherT.fromOptionF[F, String, PackageRDS](
        for {
          v <- rdsRepository.get(target.id)
          x <- convertToResponse(target.id, v.getOrElse(Set.empty)).map(Some(_))
        } yield x,
        "calcuration not completed"
      )
    } yield res).value
  }

  def add(pack: LibraryPackage) =
    for {
      defined <- packageRepo.findOne(pack.name, pack.version.original).map(_.isDefined)
      _ <- if (defined) F.raiseError(new Throwable("added package is already exists")) else F.pure(())
      id <- packageRepo.getMaxId().map(_ + 1)
      _ <- packageRepo.insert(LibraryPackage(pack.name, pack.version, pack.deps, id, pack.shasum, pack.integrity))
      _ <- addedQueue.push(id)
    } yield ()

  private def getLatestRange(name: String) =
    packageRepo
      .findByName(name)
      .map(ps => {
        val z = ps
          .filter(p => p.version.build.isEmpty && p.version.preRelease.isEmpty)
          .sortWith((l, r) => l.version > r.version)
          .head
          .version
          .original
        "^" + z
      })

  def app(): HttpApp[F] = {
    import dsl._
    implicit val libraryEncoder = LibraryPackage.encoder
    import org.http4s.circe.CirceEntityEncoder._
    implicit val libraryDecoder = LibraryPackage.decoder
    import org.http4s.circe.CirceEntityDecoder._

    HttpRoutes
      .of[F] {
        case GET -> Root / "packages" / name =>
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

        case GET -> Root / "calculated" / name :? RangeQueryParamMatcher(r) =>
          for {
            range <- if (r.isDefined) F.pure(r.get) else getLatestRange(name)
            request = PackageRequest(name, range)
            res <- getPackages(request).flatMap(_ match {
              case Right(v) => Ok(v)
              case Left(v)  => NotFound(v)
            })
          } yield res

        case req @ POST -> Root / "add" =>
          for {
            v <- req.as[LibraryPackage]
            x <- Ok(add(v))
          } yield x
      }
      .orNotFound
  }
}

case class PackageRequest(name: String, range: String)

case class PackageRDS(
    target: LibraryPackage,
    packages: Set[LibraryPackage]
)

case class PackageRDSResponse(
    request: PackageRequest,
    rds: PackageRDS
)

object RangeQueryParamMatcher extends OptionalQueryParamDecoderMatcher[String]("range")
