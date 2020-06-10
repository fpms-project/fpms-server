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
import scala.util.Try

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
  private val pack_convert =
    packs.flatMap(r => r.versions.map(v => PackageInfo(r.name, v.version, v.dep.getOrElse(Map.empty))))
  private val packs_map: Map[String, Seq[PackageInfo]] =
    packs.map(r => (r.name, r.versions.map(v => PackageInfo(r.name, v.version, v.dep.getOrElse(Map.empty))))).toMap

  def registerPackages(): F[Unit] = {
    val pack_nodep = pack_convert.filter(_.dep.isEmpty)
    val pack_dep = pack_convert.filter(_.dep.nonEmpty)
    logger.info(s"package length: ${pack_convert.length}")
    logger.info(s"package type: ${packs_map.size}")
    algo()
    F.pure(())
    /*
    for {
      // パッケージのすべての基本情報を保存
      _ <-
        packs
          .map(v =>
            if (v.versions.nonEmpty)
              infoRepository.storeVersions(
                v.versions.map(x => PackageInfo(v.name, x.version, x.dep.getOrElse(Map.empty)))
              )
            else F.unit // ないことがあるらしい……。
          )
          .runConcurrentry
      _ <- F.pure(logger.info("added all package version"))
      // 一つも依存関係がないバージョンしかないパッケージについて依存関係を保存
      _ <- alldepRepo.storeMultiEmpty(pack_nodep.map(_.base))
      _ <- F.pure(logger.info("added simple packages"))
    } yield {
      algo()
    }
     */
  }

  def algo() {
    logger.info("call algo")
    val cache = scala.collection.mutable.Map.empty[(String, String), PackageInfo]
    val array = scala.collection.mutable.ArrayBuffer.empty[PackageNode]
    // それぞれのパッケージをNodeに変換する
    for (i <- 0 to pack_convert.size - 1) {
      if (i % 100000 == 0) {
        logger.info(s"count : ${i}, length: ${array.size}")
      }
      val pack = pack_convert(i)
      try {
        if (pack.dep.isEmpty) {
          array += PackageNode(pack, Seq.empty, true, Map.empty)
        } else {
          val depsx = scala.collection.mutable.ArrayBuffer.empty[PackageInfo]
          var failed = false
          var j = pack.dep.size - 1
          while (!failed && j > -1) {
            val d = pack.dep.toSeq.apply(j)
            if (cache.get(d).isDefined) {
              depsx += cache(d)
            } else {
              var depP = for {
                ds <- packs_map.get(d._1)
                depP <- latestP(ds, d._2)
              } yield depP
              depP match {
                case Some(v) => {
                  cache.update(d, v)
                  depsx += v
                }
                case None => failed = true
              }
            }
            j -= 1
          }
          if (!failed) array += PackageNode(pack, depsx.toArray.toSeq, true, Map.empty)
        }
      } catch {
        case e: Throwable => {
          logger.error(s"${e.getStackTrace().mkString("\n")}")
        }
      }
    }
    val package_nodes = array.toArray
    logger.info(s"get count : ${package_nodes.length}")
    val map = scala.collection.mutable.Map[PackageInfo, PackageNode](package_nodes.map(x => (x.src, x)): _*)
    var count = 0
    logger.info("start loop")
    val b = new Breaks
    b.breakable {
      while (true) {
        logger.info(s"count ${count}")
        val ok = map.values.map(node => {
          val updated = node.directed
            .map(d => {
              val v = map.get(d).fold(Map.empty[PackageInfo, Seq[PackageInfo]])(_.packages)
              (d, (v.keys ++ v.values.flatten).toSeq)
            })
            .toMap
          val result = node.packages == updated
          map.update(node.src, node.copy(changeFromBefore = result, packages = updated))
          result
        })
        if (ok.forall(x => x)) {
          b.break()
        }
        logger.info(s"complete length: ${ok.filter(x => x).size}")
        count += 1
      }
    }
    logger.info("complete!")
  }

  import fpms.VersionCondition._
  def latestP(vers: Seq[PackageInfo], condition: String): Option[PackageInfo] = {
    for (i <- vers.length - 1 to 0 by -1) {
      if (condition.valid(SemVer(vers(i).version))) {
        return Some(vers(i))
      }
    }
    None
  }

  def createGraph(first: PackageInfo) = {
    val node = Node(first, true, false, false, Map.empty)
    for {
      _ <- F.pure(logger.info(s"create graph ${first.name}, ${first.version}"))
      graph <- MVar.of[F, Graph[PackageInfoBase, HyperEdge]](Graph(first.base))
      map <- MVar.of[F, Map[PackageInfoBase, Node]](Map(first.base -> node))
      _ <- F.delay({
        val b = new Breaks
        var complete = false
        b.breakable {
          while (true) {
            try {
              // 子パッケージを取得していないパッケージについて取得
              F.toIO(
                  map.read
                    .map(_.values.filter(!_.fetchPackages))
                    .flatMap(x => x.map(t => fetchPackages(t, graph, map)).runConcurrentry)
                )
                .unsafeRunSync()
            } catch {
              case e: Throwable => {
                logger.info(s"${e.toString()}")
                b.break
              }
            }
            // パッケージの集合を更新し、変わっていないかどうかを返す
            // 子パッケージの集合が一つとして変わっていなかった場合、breakする
            if (checkGraph(first.base, graph, map)) {
              complete = true
              b.break
            }
            F.toIO(
                graph.read.map(v => logger.info(s"graph: ${first.name}, ${first.version} ${v}"))
              )
              .unsafeRunSync()
          }
        }
        if (complete) {
          F.toIO(map.read.flatMap(_.map(v => alldepRepo.store(v._1, v._2.packages)).runConcurrentry)).unsafeRunSync()
          F.toIO(
              graph.read.flatMap(
                _.nodes
                  .map(x => depRelationRepository.addMulti(x.diSuccessors.map(dis => (x.name, dis.value)).toSeq))
                  .runConcurrentry
              )
            )
            .unsafeRunSync()
        }
      })
      _ <- alldepRepo.count().map(count => logger.info(s"current count ${count}"))
    } yield ()
  }

  def checkGraph(
      start: PackageInfoBase,
      graphm: MVar[F, Graph[PackageInfoBase, HyperEdge]],
      mapm: MVar[F, Map[PackageInfoBase, Node]]
  ): Boolean = {
    val graph = F.toIO(graphm.read).unsafeRunSync()
    val mumap = scala.collection.mutable.Map[PackageInfoBase, Node](F.toIO(mapm.take).unsafeRunSync().toSeq: _*)
    val startNode = graph get start
    def updateNode(
        node: graph.NodeT,
        parents: Seq[PackageInfoBase],
        mumap: scala.collection.mutable.Map[PackageInfoBase, Node]
    ): Node = {
      if (mumap.get(node.value).exists(_.completeCalculate)) return mumap.get(node.value).get
      val childs = node.diSuccessors
      val deps = childs
        .map(c => {
          if (parents.contains(c.value)) {
            mumap.get(c.value).get
          } else {
            updateNode(c, parents :+ node.value, mumap)
          }
        })
        .map(v => (v.src.base.toString(), v.packages.values.flatten.toSeq))
        .toMap
      val result = mumap
        .get(node.value)
        .map(before => before.copy(packages = deps, changeFromBefore = deps != before.packages))
        .get
      mumap.update(node.value, result)
      result
    }
    updateNode(startNode, Seq.empty, mumap)
    F.toIO(mapm.put(mumap.toMap)).unsafeRunSync()
    mumap.forall(p => !p._2.changeFromBefore)
  }

  def fetchPackages(
      target: Node,
      graph: MVar[F, Graph[PackageInfoBase, HyperEdge]],
      map: MVar[F, Map[PackageInfoBase, Node]]
  ) = {
    def createNode(v: PackageInfo): F[Option[Node]] = {
      for {
        has <- map.read.map(_.get(v.base).isDefined)
        result <-
          if (!has)
            alldepRepo
              .get(v.name, v.version)
              .map(_ match { // すでに保存されている場合はそのデータを用いる
                case Some(value) => Some(Node(v, false, true, true, value))
                // まだ保存されていない場合は空のNodeを作ってあげる
                case None => Some(Node(v, true, false, false, Map.empty))
              })
          else F.pure[Option[Node]](None)
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
              if (x._2.isEmpty) {
                throw new Error(s"${x._1._1} not found")
              } else {
                x._2.get.filter(_.version == latest(versions.get, x._1._2)).head
              }
            })
          )
          .handleError(e => {
            throw e
          })
      // Graphを更新(重複していてもGraph側で対処してくれるので問題ない)
      updated <- graph.take.map(v => v ++ (deps.map(v => (target.src.base ~> v.base))))
      _ <- graph.put(updated)
      // もしすでに保存されていたらそのデータからNodeを作成、そうじゃない場合は空のNodeを作成
      _ <-
        deps
          .map(v =>
            createNode(v).flatMap(_ match {
              case Some(v) => map.take.map(_.updated(v.src.base, v)).flatMap(x => map.put(x))
              case None    => F.unit
            })
          )
          .runConcurrentry
      // 最後に自分自身のNodeを更新
      _ <- map.take.map(_.updated(target.src.base, target.copy(fetchPackages = true))).flatMap(v => map.put(v))
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
                  .map(_.toList.map(x => (x._1._1, latest(x._2.map(_.map(_.version)).get, x._1._2).get)))
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
  def latest(vers: Seq[String], condition: String): Option[String] = {
    Try {
      vers.filter(ver => condition.valid(SemVer(ver))).seq.sortWith((x, y) => SemVer(x) > SemVer(y)).headOption
    }.getOrElse(None)
  }

  implicit class RunConcurrentry[F[_], A](val src: TraversableOnce[F[A]])(implicit
      F: ConcurrentEffect[F],
      P: Parallel[F]
  ) {
    def runConcurrentry = src.toList.parSequence
  }

  case class PackageNode(
      src: PackageInfo,
      directed: Seq[PackageInfo],
      changeFromBefore: Boolean,
      packages: Map[PackageInfo, Seq[PackageInfo]]
  )

  case class Node(
      src: PackageInfo,
      changeFromBefore: Boolean,
      fetchPackages: Boolean,
      completeCalculate: Boolean,
      packages: Map[String, Seq[PackageInfoBase]]
  ) {
    override def equals(other: Any) =
      other match {
        case that: Node => that.src.base == src.base
        case _          => false
      }
  }

}
