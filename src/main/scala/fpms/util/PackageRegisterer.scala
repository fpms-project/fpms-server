package fpms

import cats.Parallel
import cats.effect.ConcurrentEffect
import cats.implicits._
import com.gilt.gfc.semver.SemVer
import cats.effect.concurrent.{MVar, Semaphore}
import org.slf4j.LoggerFactory

class PackageRegisterer[F[_]](
    infoRepository: PackageInfoRepository[F],
    depRelationRepository: PackageDepRelationRepository[F],
    allDepRepository: PackageAllDepRepository[F],
    var packs: Seq[RootInterface]
)(implicit F: ConcurrentEffect[F], P: Parallel[F]) {

  private val logger = LoggerFactory.getLogger(this.getClass)
  private val registering = MVar.of[F, Map[String, Semaphore[F]]](Map.empty)

  import PackageRegisterer._
  def registerPackages(): F[Unit] = {
    for {
      _ <-
        packs
          .filter(v => v.versions.forall(!_.dep.exists(_.nonEmpty)))
          .map(v =>
            savePackage(v.name).handleError(e => {
              logger.warn(
                s"${v.name} package error: ${e.toString()} ${e.getStackTrace().mkString("\n")}"
              )
            })
          )
          .toList
          .toNel
          .get
          .parSequence_
      _ <-
        packs
          .map(v =>
            savePackage(v.name).handleError(e => {
              logger.warn(
                s"${v.name} package error: ${e.toString()} ${e.getStackTrace().mkString("\n")}"
              )
            })
          )
          .toList
          .toNel
          .get
          .parSequence_
    } yield ()

  }

  def savePackage(name: String): F[Unit] = {
    for {
      reg <- registering
      contains <- reg.read.map(_ contains name)
      _ <-
        if (!contains) {
          for {
            s <- Semaphore[F](1)
            _ <- s.acquire
            m <- reg.take.map(_.updated(name, s))
            _ <- reg.put(m)

            _ <- registerPackage(name)
            // modosu
            _ <- s.release
            m <- reg.take.map(_.updated(name, s))
            _ <- reg.put(m)
          } yield ()
        } else {
          for {
            reg <- registering
            v <- reg.read.map(_.get(name))
            _ <- v.get.acquire
          } yield ()
        }
    } yield ()
  }

  def registerPackage(name: String): F[Unit] = {
    val info = packs.filter(_.name == name).head
    for {
      //追加作業
      _ <- F.pure(logger.info(s"start register package: ${info.name}"))
      _ <- infoRepository.storeVersions(info.name, info.versions.map(_.version))
      _ <-
        if (info.versions.forall(p => !p.dep.exists(_.nonEmpty))) {
          allDepRepository.storeMultiEmpty(
            info.versions.map(v => PackageInfoBase(info.name, v.version))
          )
        } else {
          info.versions
            .map(v => registerPackageDeps(info.name, v))
            .toList
            .toNel
            .get
            .parSequence_
        }
      _ <- F.pure(logger.info(s"complete register package: ${info.name}"))
    } yield ()
  }

  def registerPackageDeps(name: String, version: NpmPackageVersion): F[Unit] = {
    val target = PackageInfoBase(name, version.version)
    if (version.dep.exists(_.nonEmpty)) {
      for {
        deps <- version.dep.fold(F.pure(Seq.empty[(String, String)]))(
          _.map(v =>
            for {
              _ <- savePackage(v._1)
              // get latest version from condition
              v <-
                infoRepository
                  .getVersions(v._1)
                  .map(vers =>
                    (
                      v._1,
                      getLatestVersion(vers, v._2)
                    )
                  )
            } yield v
          ).toList.toNel.fold(F.pure(Seq.empty[(String, String)]))(v => v.parSequence.map(_.toList))
        )
        // Redisに保存
        _ <- depRelationRepository.addMulti(
          deps
            .map(v => (v._1, target))
        )
        // get all deps
        allDeps <-
          deps
            .map(v => allDepRepository.get(PackageInfoBase(v._1, v._2)))
            .toList
            .toNel
            .fold(F.pure(Seq.empty[PackageInfoBase]))(v =>
              v.parSequence
                .map(v =>
                  v.map[Seq[PackageInfoBase]](v => v.getOrElse(Seq.empty[PackageInfoBase]))
                    .toList
                    .flatten[PackageInfoBase]
                )
            )
        // save to repository
        _ <- allDepRepository.store(target, allDeps)
      } yield ()
    } else {
      allDepRepository.store(target, Seq.empty)
    }

  }

}

object PackageRegisterer {

  import fpms.VersionCondition._
  def getLatestVersion(vers: Option[Seq[String]], condition: String): String = {
    vers.get
      .filter(ver => condition.valid(SemVer(ver)))
      .seq
      .sortWith((x, y) => SemVer(x) > SemVer(y))
      .head
  }
}
