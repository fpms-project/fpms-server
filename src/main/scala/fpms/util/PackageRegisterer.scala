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
    alldepRepo: PackageAllDepRepository[F],
    var packs: Seq[RootInterface]
)(implicit F: ConcurrentEffect[F], P: Parallel[F]) {
  val SEMAPHORE_COUNT = Runtime.getRuntime().availableProcessors() * 2
  private val logger = LoggerFactory.getLogger(this.getClass)
  private val digdepSemaphore = F.toIO(Semaphore[F](1)).unsafeRunSync()
  private val semaphore = F.toIO(Semaphore[F](SEMAPHORE_COUNT)).unsafeRunSync()
  private val mv = F.toIO(MVar.of[F, Map[String, MVar[F, Boolean]]](Map.empty)).unsafeRunSync()
  private val registerStopper =
    F.toIO(MVar.of[F, Map[String, MVar[F, Boolean]]](Map.empty)).unsafeRunSync()

  import PackageRegisterer._
  def registerPackages(): F[Unit] = {

    val packs_nodep = packs
      .filter(v => v.versions.forall(!_.dep.exists(_.nonEmpty)))
    val packs_dep = packs
      .filter(v => v.versions.exists(_.dep.exists(_.nonEmpty)))
    for {
      // パッケージのすべての基本情報を保存
      _ <-
        packs
          .map(v =>
            if (v.versions.nonEmpty) {
              for {
                _ <- infoRepository.storeVersions(v.name, v.versions.map(_.version))
                _ <-
                  v.versions
                    .map(x =>
                      infoRepository.store(
                        PackageInfo(v.name, x.version, x.dep.getOrElse(Map.empty))
                      )
                    )
                    .toList
                    .toNel
                    .get
                    .parSequence_
              } yield ()
            } else {
              // ないことがあるらしい……。
              F.unit
            }
          )
          .toList
          .toNel
          .get
          .parSequence_
      _ <- F.pure(logger.info("added all package version"))
      // 一つも依存関係がないバージョンしかないパッケージについて依存関係を保存
      _ <-
        packs_nodep
          .map(v =>
            for {
              _ <- semaphore.acquire
              _ <- alldepRepo.storeMultiEmpty(
                v.versions.map(x => PackageInfoBase(v.name, x.version))
              )
              _ <- F.pure(logger.info(s"${v.name} added_simple_package"))
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
          .map(x => x.versions.map(ver => (x.name, ver.version)))
          .flatten
          .map(v =>
            for {
              _ <- semaphore.acquire
              _ <- savePackageDeps(v._1, v._2).handleError(e => {
                logger.warn(
                  s"${v._1} ${v._2} package error: ${e.toString()}"
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
      _ <- F.pure(logger.info("Completed!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"))
    } yield ()
  }

  def savePackageDeps(name: String, version: String): F[Boolean] = {
    val target = PackageInfoBase(name, version)
    for {
      stopper <- registerStopper.take
       _ <- F.pure(logger.info(s"$name $version save_package_deps_start"))
      result <-
        if (!(stopper contains target.toString())) {
          for {
            stop <- MVar.of[F, Boolean](false)
            _ <- stop.take
            _ <- registerStopper.put(stopper.updated(target.toString(), stop))
            result <- savePackageDepsInternal(name, version)
            _ <- stop.put(result)
          } yield result
        } else {
          for {
            _ <- registerStopper.put(stopper)
            stop <- registerStopper.read.map(_.get(target.toString()))
            result <- stop.get.read
          } yield result
        }
    } yield result
  }

  def savePackageDepsInternal(name: String, version: String): F[Boolean] = {
    val target = PackageInfoBase(name, version)
    logger.info(s"$name $version dep_calc_start")
    infoRepository
      .get(name, version)
      .flatMap(v =>
        // そもそもinfoに追加されてなかったらfalse
        v.fold(F.pure(false))(x => {
          val func = for {
            // すべての適応する最新版を取得
            v <-
              x.dep
                .map(d => infoRepository.getVersions(d._1).map(z => (d._1, latest(z, d._2))))
                .toList
                .toNel
                .fold(F.pure(Seq.empty[(String, String)]))(y => y.parSequence.map(_.toList))
            // それぞれの最新版についてそのすべての依存関係を取得
            z <-
              v.map(x =>
                  alldepRepo
                    .get(x._1, x._2)
                    .flatMap(_.fold({
                      // 追加されていなかった場合registerPackageを呼んでからallDepRepoに保存されているものを呼ぶ
                      // もしだめだったらFalseが却ってくるのでその時点で例外を投げる
                      for {
                        v <- savePackageDeps(x._1, x._2)
                        result <-
                          if (v) alldepRepo.get(x._1, x._2).map(_.get)
                          else throw new Error("dep not found!!!!")
                      } yield (x._1, result.values.flatten[PackageInfoBase].toList)
                    })(depmap => F.pure((x._1, depmap.values.flatten[PackageInfoBase].toList))))
                )
                .toList
                .toNel
                .fold(F.pure(Map.empty[String, Seq[PackageInfoBase]]))(_.parSequence
                .map(_.toList.toMap))
            // すべての依存を追加
            _ <- alldepRepo.store(target, z)
            // 依存関係を追加
            _ <- depRelationRepository.addMulti(v.map(a => (a._1, target)))
            _ <- F.pure(logger.info(s"$name $version dep_calc_complete"))
          } yield true
          func.handleError(e => {
            logger.info(s"$name $version registor_failed | error: ${e.toString()}")
            false
          })
        })
      )
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
