package fpms.server

import cats.data.EitherT
import cats.implicits.*
import com.github.sh4869.semver_parser.Range
import com.typesafe.scalalogging.LazyLogging
import io.circe.generic.auto.*
import org.http4s.HttpApp
import org.http4s.HttpRoutes
import org.http4s.dsl.*
import org.http4s.dsl.impl.OptionalQueryParamDecoderMatcher
import org.http4s.implicits.*
import cats.effect.implicits.*

import fpms.LibraryPackage
import fpms.LibraryPackageWithOutId
import fpms.repository.AddedPackageIdQueue
import fpms.repository.LibraryPackageRepository
import fpms.repository.RDSRepository
import org.http4s.Response
import org.http4s.Status
import cats.effect.kernel.Async

class ServerApp[F[_]: Async](
    packageRepo: LibraryPackageRepository[F],
    rdsRepository: RDSRepository[F],
    addedQueue: AddedPackageIdQueue[F]
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

  def add(pack: LibraryPackageWithOutId) =
    for {
      defined <- packageRepo.findOne(pack.name, pack.version.original).map(_.isDefined)
      _ <- if (defined) Async[F].raiseError(new Throwable("added package is already exists")) else Async[F].pure(())
      p <- packageRepo.insert(
        LibraryPackageWithOutId(pack.name, pack.version, pack.deps, pack.shasum, pack.integrity)
      )
      _ <- addedQueue.push(p.id)
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
    import dsl.*
    import LibraryPackage.*
    import LibraryPackageWithOutId.*

    import org.http4s.circe.CirceEntityEncoder.*
    import org.http4s.circe.CirceEntityDecoder.*

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
            range <- if (r.isDefined) Async[F].pure(r.get) else getLatestRange(name)
            request = PackageRequest(name, range)
            res <- getPackages(request).flatMap(_ match {
              case Right(v) => Ok(v)
              case Left(v)  => NotFound(v)
            })
          } yield res

        case GET -> Root =>
          Async[F].pure(Response.apply(Status.Ok, body = fs2.Stream(ServerApp.ROOT_DESCRIPTION.getBytes().toList*)))

        case req @ POST -> Root / "add" =>
          for {
            v <- req.as[LibraryPackageWithOutId]
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

object ServerApp {
  val ROOT_DESCRIPTION =
    """
<!doctype html><meta charset="utf-8"><script src="https://unpkg.com/@makenowjust/md.html@0.4.2"></script><noscript>
# API

- HOST: `http://fpms-server.cs.ise.shibaura-it.ac.jp`

## `/calculated/$name`

returns the calculated `$name` package.

### optional query

- `range`: version range, see also [semver.npmjs.com](https://semver.npmjs.com/)

### response example

```json
{
  "target": {
    "name": "react",
    "version": "17.0.1",
    "dep": {
      "loose-envify": "^1.1.0",
      "object-assign": "^4.1.1"
    },
    "shasum": "6e0600416bd57574e3f86d92edba3d9008726127",
    "integrity": "sha512-lG9c9UuMHdcAexXtigOZLX8exLWkW0Ku29qPRU8uhF2R9BN96dLCt0psvzPLlHc5OWkgymP3qwTRgbnw5BKx3w=="
  },
  "packages": [
    {
      "name": "object-assign",
      "version": "4.1.1",
      "dep": {},
      "shasum": "2109adc7965887cfc05cbbd442cac8bfbb360863",
      "integrity": ""
    },
    {
      "name": "loose-envify",
      "version": "1.4.0",
      "dep": {
        "js-tokens": "^3.0.0 || ^4.0.0"
      },
      "shasum": "71ee51fa7be4caec1a63839f7e682d8132d30caf",
      "integrity": "sha512-lyuxPGr/Wfhrlem2CL/UcnUc1zcqKAImBDzukY7Y5F/yQiNdko6+fRLevlw1HgMySw7f611UIY408EtxRSoK3Q=="
    },
    {
      "name": "js-tokens",
      "version": "4.0.0",
      "dep": {},
      "shasum": "19203fb59991df98e3a287050d4647cdeaf32499",
      "integrity": "sha512-RdJUflcE3cUzKiMqQgsCu06FPu9UdIJO0beYbPhHN4k6apgJtifcoCtT9bcxOpYBtpD2kCM6Sbzg4CausW/PKQ=="
    }
  ]
}
```

## `POST /add`

added packages to the server.

### body format

```
{
  "id": 0, // always 0
  "name": "package_name", // package name 
  "version": "0.0.0", // semantic version
  "deps": {}, // deps of packages
  "shasum": "" // shasum of package. It can get in npm or yarn api.
}
```
"""
}
