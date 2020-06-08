package fpms

import cats.effect.{ContextShift, Timer}
import scala.concurrent.CancellationException
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration._
import cats.Parallel
import cats.effect.ConcurrentEffect
import cats.implicits._
import com.gilt.gfc.semver.SemVer
import cats.effect.concurrent.{MVar, Semaphore}
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.net.URLEncoder
import scalax.collection.Graph // or scalax.collection.mutable.Graph
import scalax.collection.GraphPredef._, scalax.collection.GraphEdge._
import scala.util.control.Breaks

class PackageRegisterer[F[_]](
    infoRepository: PackageInfoRepository[F],
    depRelationRepository: PackageDepRelationRepository[F],
    alldepRepo: PackageAllDepRepository[F],
    var packs: Seq[RootInterface]
)(implicit F: ConcurrentEffect[F], P: Parallel[F], timer: Timer[F], cs: ContextShift[F]) {

  import PackageRegisterer._

  private val logger = LoggerFactory.getLogger(this.getClass)
  private val semaphore = F.toIO(Semaphore[F](SEMAPHORE_COUNT)).unsafeRunSync()
  private val registerStopper =
    F.toIO(MVar.of[F, Map[String, MVar[F, Option[Map[String, Seq[PackageInfoBase]]]]]](Map.empty)).unsafeRunSync()

  def registerPackages(): F[Unit] = {
    val packs_nodep = packs.filter(v => v.versions.forall(!_.dep.exists(_.nonEmpty)))
    val packs_dep = packs.filter(v => v.versions.exists(_.dep.exists(_.nonEmpty)))
    val pack_dep = packs.flatMap(_.versions).filter(_.dep.exists(_.nonEmpty))
    val pack_non_dep = packs.flatMap(_.versions).filter(_.dep.forall(_.isEmpty))
    for {
      // パッケージのすべての基本情報を保存
      _ <-
        packs
          .map(v =>
            if (v.versions.nonEmpty) {
              for {
                _ <- infoRepository.storeVersions(
                  v.versions.map(x => PackageInfo(v.name, x.version, x.dep.getOrElse(Map.empty)))
                )
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
          .runConcurrentry
          .map(_ => Unit)
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
          .runConcurrentry
          .map(_ => Unit)
      _ <- F.pure(logger.info("added simple packages"))
      _ <- semaphore.acquireN(8)
      _ <- semaphore.available.map(x => logger.info(s"semaphore number: ${x}"))
      _ <-
        packs_dep
          .slice(0, 1000)
          .map(x => x.versions.map(ver => (x.name, ver.version)))
          .flatten
          .map(v =>
            for {
              _ <- semaphore.acquire
              _ <- F.pure(logger.info(s"${v._1} ${v._2} save_package_call"))
              _ <- savePackageDeps(v._1, v._2).handleError(e => {
                logger.warn(
                  s"${v._1} ${v._2} package error: ${e.toString()}"
                )
                None
              })
              _ <- semaphore.release
            } yield ()
          )
          .runConcurrentry
          .map(_ => Unit)
      _ <- F.pure(logger.info("Completed!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"))
    } yield ()
  }

  def createGraph(first: PackageInfoBase) = {
    for {
      graph <- MVar.of[F, Graph[PackageInfoBase, HyperEdge]](Graph(first))
      map <- MVar.of[F, Map[PackageInfoBase, Node]](Map.empty)
      _ <- F.delay({
        val b = new Breaks
        b.breakable {
          while (true) {
            // 子パッケージを取得していないパッケージについて取得
            F.toIO(
                map.read
                  .map(_.values.filter(!_.fetchPackages))
                  .flatMap(x => x.map(t => fetchPackages(t, graph, map)).runConcurrentry)
              )
              .unsafeRunSync()
            // パッケージの集合を更新し、変わっていないかどうかを返す
            // 子パッケージの集合が一つとして変わっていなかった場合、breakする
            if (checkGraph(first, graph, map)) {
              b.break()
            }
          }
        }
      })
    } yield ()
  }

  def checkGraph(
      start: PackageInfoBase,
      graph: MVar[F, Graph[PackageInfoBase, HyperEdge]],
      map: MVar[F, Map[PackageInfoBase, Node]]
  ): Boolean = {
    // TODO: ここでグラフのそれぞれのNodeの更新と計算をやる
    false
  }

  def fetchPackages(
      target: Node,
      graph: MVar[F, Graph[PackageInfoBase, HyperEdge]],
      map: MVar[F, Map[PackageInfoBase, Node]]
  ) = {
    def createNode(v: PackageInfo): F[Option[Node]] = {
      for {
        has <- map.read.map(_.get(v.toBaseInfo).isDefined)
        result <-
          if (has) {
            alldepRepo
              .get(v.name, v.version)
              .map(_ match {
                // すでに保存されている場合はそのデータを用いる
                case Some(value) => Some(Node(v, false, true, true, value))
                // まだ保存されていない場合は空のNodeを作ってあげる
                case None => Some(Node(v, true, false, false, Map.empty))
              })
          } else {
            F.pure[Option[Node]](None)
          }
      } yield result
    }
    for {
      // 依存パッケージを取得
      deps <-
        target.src.dep
          .map(d => infoRepository.getVersions(d._1).map(z => (d, z)))
          .runConcurrentry
          .map(
            _.map(x => {
              val versions = x._2.map(_.map(_.version))
              x._2.get.filter(_.version == latest(versions, x._1._2)).head
            })
          )
          .handleError(e => {
            throw e
          })
      // Graphを更新(重複していてもGraph側で対処してくれるので問題ない)
      updated <- graph.take.map(v => v ++ (deps.map(v => (target.src.toBaseInfo ~> v.toBaseInfo))))
      _ <- graph.put(updated)
      // もしすでに保存されていたらそのデータからNodeを作成、そうじゃない場合は空のNodeを作成
      _ <-
        deps
          .map(v => {
            createNode(v).map(_ match {
              case Some(v) => map.take.map(_.updated(v.src.toBaseInfo, v)).flatMap(x => map.put(x))
              case None    => F.unit
            })
          })
          .runConcurrentry
      // 最後に自分自身のNodeを更新
      _ <- map.take.map(_.updated(target.src.toBaseInfo, target.copy(fetchPackages = true))).flatMap(v => map.put(v))
    } yield ()
  }

  def savePackageDeps(
      name: String,
      version: String,
      parents: Seq[String] = Seq.empty
  ): F[Option[Map[String, Seq[PackageInfoBase]]]] = {
    val target = PackageInfoBase(name, version)
    val memories = parents.map(parent => Seq(target.toString(), parent.toString()).sorted)
    for {
      stopper <- registerStopper.take
      _ <- F.pure(
        logger.info(
          s"$name $version save_package_deps_start : ${parents.mkString(",")}"
        )
      )
      result <-
        if (!(stopper contains target.toString())) {
          for {
            stop <- MVar.of[F, Option[Map[String, Seq[PackageInfoBase]]]](None)
            _ <- stop.take
            _ <- registerStopper.put(stopper.updated(target.toString(), stop))
            // 登録
            result <- timeout(savePackageDepsInternal(name, version, parents), 5.second).handleError(_ => {
              logger.info(s"$name $version timeout_catched!!!")
              None
            })
            _ <- stop.put(result)
            updated <- registerStopper.take.map(_.updated(target.toString(), stop))
            _ <- registerStopper.put(updated)
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

  def savePackageDepsInternal(
      name: String,
      version: String,
      parents: Seq[String]
  ): F[Option[Map[String, Seq[PackageInfoBase]]]] = {
    val target = PackageInfoBase(name, version)
    val get: F[Option[Map[String, Seq[PackageInfoBase]]]] = for {
      _ <- F.pure(logger.info(s"$name $version dep_calc_start"))
      targ <- infoRepository.get(name, version)
      result <-
        // もし存在しなかったらFalse
        if (targ.isEmpty) {
          F.pure(logger.info(s"$name $version dep_calc_failed | error: not found"))
            .as[Option[Map[String, Seq[PackageInfoBase]]]](None)
        } else {
          val targetPack = targ.get
          if (targetPack.dep.isEmpty) {
            for {
              _ <- alldepRepo.store(target, Map.empty)
              _ <- F.pure(logger.info(s"$name $version dep_calc_complete (empty)"))
            } yield Some(Map.empty[String, Seq[PackageInfoBase]])
          } else {
            for {
              z <-
                targ.get.dep
                  .map(d => infoRepository.getVersions(d._1).map(z => (d, z)))
                  .runConcurrentry
                  .map(_.toList.map(x => (x._1._1, latest(x._2.map(_.map(_.version)), x._1._2))))
                  .handleError(e => {
                    logger.info(s"$name $version dep_calc_failed_on_get_latest_version ${e.toString()}")
                    throw e
                  })
              _ <- F.pure(logger.info(s"$name $version dep_calc_get_latests_version"))
              // とりあえず最初に取得する。Optionの可能性がある
              first <-
                z.map(x => alldepRepo.get(x._1, x._2).map(d => (x, d)))
                  .runConcurrentry
                  .map(_.toList)
                  .handleError(e => {
                    logger.info(s"$name $version dep_calc_failed_on_get_deps_first ${e.toString()}")
                    throw e
                  })
              _ <- F.pure(logger.info(s"$name $version dep_calc_get_deps_first"))
              // Optionな場合はsavePackageDepsを呼んでもう一度
              result <-
                first
                  .map(x =>
                    x._2.fold(
                      savePackageDeps(x._1._1, x._1._2, parents :+ target.toString())
                        .map(v => (x._1._1, v))
                        .handleError(v => (x._1._1, None))
                    )(dep => F.pure((x._1._1, Some(dep))))
                  )
                  .runConcurrentry
                  .map(
                    _.toList.toMap.map(x => (x._1, x._2.get.values.flatten[PackageInfoBase].toList))
                  )
                  .handleError(e => {
                    logger.info(s"$name $version dep_calc_failed_on_get_deps_second ${e.toString()}")
                    throw e
                  })
              _ <- F.pure(logger.info(s"$name $version dep_calc_get_deps_second"))
              _ <- alldepRepo.store(target, result)
              _ <- depRelationRepository.addMulti(z.map(a => (a._1, target)))
              _ <- F.pure(logger.info(s"$name $version dep_calc_complete"))
            } yield Some(result)
          }
        }
    } yield result
    get.handleError(e => {
      // logger.info(s"$name $version dep_calc_failed | error: ${e.toString()}")
      None
    })
  }
  def timeoutTo[F[_], A](fa: F[A], after: FiniteDuration, fallback: F[A])(implicit
      timer: Timer[F],
      cs: ContextShift[F],
      F: ConcurrentEffect[F]
  ): F[A] = {
    F.race(fa, timer.sleep(after)).flatMap {
      case Left(a) => F.pure(a)
      case Right(_) => {
        fallback
      }
    }
  }

  def timeout[F[_], A](fa: F[A], after: FiniteDuration)(implicit
      timer: Timer[F],
      cs: ContextShift[F],
      F: ConcurrentEffect[F]
  ): F[A] = {
    logger.info("timeout_setting!!!")
    val error = new CancellationException(after.toString)
    timeoutTo(fa, after, F.raiseError(error))
  }
}

object PackageRegisterer {

  val SEMAPHORE_COUNT = Runtime.getRuntime().availableProcessors()

  import fpms.VersionCondition._
  def latest(vers: Option[Seq[String]], condition: String): String = {
    vers.get.filter(ver => condition.valid(SemVer(ver))).seq.sortWith((x, y) => SemVer(x) > SemVer(y)).head
  }

  implicit class RunConcurrentry[F[_], A](val src: TraversableOnce[F[A]])(implicit
      F: ConcurrentEffect[F],
      P: Parallel[F]
  ) {
    def runConcurrentry = src.toList.parSequence
  }

  case class Node(
      src: PackageInfo,
      changeFromBefore: Boolean,
      fetchPackages: Boolean,
      completeCalculate: Boolean,
      packages: Map[String, Seq[PackageInfoBase]]
  ) {
    override def equals(other: Any) =
      other match {
        case that: Node => that.src.toBaseInfo == src.toBaseInfo
        case _          => false
      }
  }

}
