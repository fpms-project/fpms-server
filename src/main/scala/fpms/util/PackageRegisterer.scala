package fpms

import cats.Parallel
import cats.effect.ConcurrentEffect
import cats.implicits._
import com.gilt.gfc.semver.SemVer
import cats.effect.concurrent.MVar

import org.slf4j.LoggerFactory

class PackageRegisterer[F[_]](
    infoRepository: PackageInfoRepository[F],
    depRelationRepository: PackageDepRelationRepository[F],
    allDepRepository: PackageAllDepRepository[F],
    var packs: Seq[RootInterface]
)(implicit F: ConcurrentEffect[F], P: Parallel[F]) {

  private val logger = LoggerFactory.getLogger(this.getClass)
  private val registering = MVar.of[F, Map[String, MVar[F, Boolean]]](Map.empty)
  private var startedPackage: Set[String] = Set.empty[String]

  import PackageRegisterer._
  def registerPackages(): F[Unit] = {
    packs
      .map(v =>
        registerPackage(v).handleError(e => {
          logger.warn(
            s"${v.name} package error: ${e.toString()} ${e.getStackTrace().mkString("\n")}"
          )
        })
      )
      .toList
      .toNel
      .get
      .parSequence_
  }

  def registerPackage(info: RootInterface): F[Unit] = {
    for {
      reg <- registering
      contains <- reg.read.map(_ contains info.name)
      _ <-
        if (!contains) {
          for {
            mva <- MVar.of[F, Boolean](false)
            _ <- mva.take
            m <- reg.take.map(_.updated(info.name, mva))
            _ <- reg.put(m)
            //追加作業
            _ <- F.pure(logger.info(s"start register package: ${info.name}"))
            _ <- infoRepository.storeVersions(info.name, info.versions.map(_.version))
            _ <-
              info.versions.map(v => registerPackageDeps(info.name, v)).toList.toNel.get.parSequence
            _ <- F.pure(logger.info(s"complete register package: ${info.name}"))
            // modosu
            _ <- mva.put(true)
            m <- reg.take.map(_.updated(info.name, mva))
            _ <- reg.put(m)
          } yield ()
        } else {
          for {
            reg <- registering
            v <- reg.read.map(_.get(info.name))
            _ <- v.get.read
          } yield ()
        }
    } yield ()
  }

  def registerPackageDeps(name: String, version: NpmPackageVersion): F[Unit] = {
    val target = PackageInfoBase(name, version.version)
    for {
      deps <- version.dep.fold(F.pure(Seq.empty[(String, String)]))(
        _.map(v =>
          for {
            _ <- {
              val t = packs.filter(_.name == v._1)
              if (t.isEmpty) {
                throw new Error(s"${v._1} package not found")
              } else {
                registerPackage(t.head)
              }

            }
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
      _ <-
        deps
          .map(v => depRelationRepository.add(v._1, target))
          .toList
          .toNel
          .fold(F.unit)(v => v.parSequence_)
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
