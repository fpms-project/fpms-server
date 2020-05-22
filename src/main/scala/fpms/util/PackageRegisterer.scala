package fpms

import cats.Parallel
import cats.effect.ConcurrentEffect
import cats.implicits._
import com.gilt.gfc.semver.SemVer
import cats.effect.concurrent.{MVar, Semaphore}
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.net.URLEncoder

class PackageRegisterer[F[_]](
    infoRepository: PackageInfoRepository[F],
    depRelationRepository: PackageDepRelationRepository[F],
    allDepRepository: PackageAllDepRepository[F],
    var packs: Seq[RootInterface]
)(implicit F: ConcurrentEffect[F], P: Parallel[F]) {
  val SEMAPHORE_COUNT = Runtime.getRuntime().availableProcessors() * 2
  private val logger = LoggerFactory.getLogger(this.getClass)
  private val digdepSemaphore = F.toIO(Semaphore[F](1)).unsafeRunSync()
  private val semaphore = F.toIO(Semaphore[F](SEMAPHORE_COUNT)).unsafeRunSync()
  private val mv = F.toIO(MVar.of[F, Map[String, MVar[F, Boolean]]](Map.empty)).unsafeRunSync()

  import PackageRegisterer._
  def registerPackages(): F[Unit] = {

    val packs_nodep = packs
      .filter(v => v.versions.forall(!_.dep.exists(_.nonEmpty)))
    val packs_dep = packs
      .filter(v => v.versions.exists(_.dep.exists(_.nonEmpty)))
    assert(packs_dep.length + packs_nodep.length == packs.length, "pack length check")
    for {
      _ <-
        packs_nodep
          .map(v =>
            for {
              _ <- semaphore.acquire
              _ <- savePackage(v.name).handleError(e => {
                logger.warn(
                  s"${v.name} package error: ${e.toString()}"
                )
                false
              })
              _ <- semaphore.release
            } yield ()
          )
          .toList
          .toNel
          .get
          .parSequence_
      _ <- F.pure(logger.info("added simple packages"))
      _ <- semaphore.acquireN(SEMAPHORE_COUNT / 2)
      _ <-
        packs_dep
          .map(v =>
            for {
              _ <- semaphore.acquire
              _ <- savePackage(v.name).handleError(e => {
                logger.warn(
                  s"${v.name} package error: ${e.toString()}"
                )
                false
              })
              _ <- semaphore.release
            } yield ()
          )
          .toList
          .toNel
          .get
          .parSequence_
    } yield ()
  }

  def savePackage(name: String, fromParent: Boolean = false): F[Boolean] = {
    for {
      mvv <- mv.take
      _ <- F.pure(logger.info(s"$name save_package_start"))
      result <-
        if (!(mvv contains name)) {
          for {
            _ <- F.pure(
              logger.info(
                s"$name save_package_run : ${if (fromParent) "fromParent" else "fromRegister"}"
              )
            )
            v <- MVar.of[F, Boolean](false)
            _ <- v.take
            _ <- mv.put(mvv.updated(name, v))
            x <- registerPackage(name, fromParent).handleError(v => false)
            // modosu
            _ <- v.put(true)
            m <- mv.take.map(_.updated(name, v))
            _ <- mv.put(m)
          } yield x
        } else {
          for {
            _ <- mv.put(mvv)
            v <- mv.read.map(_.get(name))
            x <- v.get.read
          } yield x
        }
    } yield result
  }

  // パッケージそのものが見つからない場合はFalse
  // パッケージが一つでも見つかっている場合はTrue
  def registerPackage(name: String, fromParent: Boolean): F[Boolean] = {
    // TODO: 本当はinfoRepositoryに追加するのは依存関係が計算できたやつだけ
    packs.filter(_.name == name).headOption.fold(F.pure(false)) { info =>
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
              .map(v => registerPackageDeps(info.name, v, fromParent).handleError(e => false))
              .toList
              .toNel
              .get
              .parSequence_
          }
        _ <- F.pure(logger.info(s"complete register package: ${info.name}"))
      } yield true
    }
  }

  def registerPackageDeps(
      name: String,
      version: NpmPackageVersion,
      fromParent: Boolean
  ): F[Boolean] = {
    val target = PackageInfoBase(name, version.version)
    if (version.dep.forall(_.isEmpty)) {
      for {
        _ <- F.pure(logger.info(s"$name ${version.version} version_start (nodep)"))
        _ <- allDepRepository.store(target, Seq.empty)
        _ <- F.pure(logger.info(s"$name ${version.version} version_complete (nodep)"))
      } yield true
    } else {
      for {
        _ <- F.pure(logger.info(s"$name ${version.version} version_start"))
        deps <- version.dep.fold(F.pure(Seq.empty[(String, String)]))(
          _.map(v => {
            val f: F[Option[(String, String)]] = for {
              r <- savePackage(v._1, true)
              _ <- if (r) F.unit else { throw new Error("!!!!") }
              v <-
                infoRepository
                  .getVersions(v._1)
                  .map(vers =>
                    (
                      v._1,
                      latest(vers, v._2)
                    )
                  )
            } yield Some(v)
            f.handleError(_ => None)
          }).toList.toNel.fold(F.pure(Seq.empty[(String, String)]))(v =>
            v.parSequence.map(_.toList.flatten)
          )
        )
        result <-
          if (deps.length == version.dep.fold(0)(v => v.size)) {
            for {
              _ <- F.pure(logger.info(s"$name ${version.version} version_dep_calculate_success"))
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
              _ <- F.pure(logger.info(s"$name ${version.version} version_complete"))
            } yield true
          } else {
            F.pure(logger.info(s"$name ${version.version} version_failed")).map(_ => false)
          }
      } yield result
    }

  }

}

object PackageRegisterer {

  import fpms.VersionCondition._
  def latest(vers: Option[Seq[String]], condition: String): String = {
    vers.get
      .filter(ver => condition.valid(SemVer(ver)))
      .seq
      .sortWith((x, y) => SemVer(x) > SemVer(y))
      .head
  }
}
