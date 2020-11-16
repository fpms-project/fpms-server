package fpms.calcurator

import scala.util.Try

import cats.effect.ConcurrentEffect
import cats.implicits._
import com.github.sh4869.semver_parser.Range
import com.github.sh4869.semver_parser.SemVer
import com.redis.RedisClient
import com.redis.serialization.Parse.Implicits._
import org.slf4j.LoggerFactory

import fpms.LibraryPackage
import fpms.calcurator.VersionFinder._
import fpms.repository.LibraryPackageRepository

class RedisDependecyCalculator[F[_]](redis: RedisClient, spRepo: LibraryPackageRepository[F])(
    implicit F: ConcurrentEffect[F]
) extends DependencyCalculator {

  private val logger = LoggerFactory.getLogger(this.getClass)

  def initialize(): Unit = {
    redis.flushall
    val x = new LocalDependencyCalculator
    x.initialize()
    saveInitializeList(x.getMap)
  }

  private def saveInitializeList(map: Map[Int, PackageNode]) {
    // すべてのIDを保存
    redis.set(allIdSetKey, map.keySet.mkString(","))
    map
      .grouped(100)
      .zipWithIndex
      .foreach(v => {
        if (v._2 * 100 % 1000000 == 0) logger.info(s"${v._2 * 100}")
        // パッケージNodeそれぞれについて直接依存と間接依存をキーにしてデータを保存する
        val kvs = v._1
          .map(x => {
            if (x._2.directed.size > 0) {
              Seq((directedKey(x._1), x._2.directed.mkString(",")), (packagesKey(x._1), x._2.packages.mkString(",")))
            } else {
              Seq((packagesKey(x._1), x._2.packages.mkString(",")))
            }
          })
          .flatten
        redis.mset(kvs.toSeq: _*)
      })
  }

  def get(id: Int): Option[PackageNode] =
    Some(
      PackageNode(
        id,
        redis.get[String](directedKey(id)).map(splitRedisData(_)).getOrElse(Seq.empty),
        scala.collection.mutable.Set(redis.get[String](packagesKey(id)).map(splitRedisData(_)).getOrElse(Seq.empty): _*)
      )
    )

  def load(): Unit = ???

  def add(added: AddPackage): Unit = {
    // 追加リクエストのパッケージを追加
    val id = F.toIO(spRepo.getMaxId()).unsafeRunSync() + 1
    val directly = scala.collection.mutable.Set.empty[LibraryPackage]
    added.deps.foreach(v => {
      val targets = F.toIO(spRepo.findByName(v._1)).unsafeRunSync()
      targets.toSeq.latestInFits(v._2) match {
        case Some(value) => directly.add(value)
        case None => {
          logger.error(s"error : not found dependency ${v._1}@${v._2}")
          return ()
        }
      }
    })
    val addedPackage = LibraryPackage(added.name, SemVer(added.version), added.deps, id)
    F.toIO(spRepo.insert(addedPackage)).unsafeRunSync()
    logger.info(s"added package: ${addedPackage.name}@${addedPackage.version.original}")

    // 追加したパッケージの直接依存と間接依存
    val addedDirectlyDeps = directly.map(_.id)
    val addedIndirectlyDeps = scala.collection.mutable.Set(directly.map(_.id).toSeq: _*)
    // 追加したパッケージによって更新されるパッケージのMap(直接依存・間接依存それぞれ)
    val updatedPackDirectlyDepsMap = scala.collection.mutable.Map.empty[Int, Set[Int]]

    logger.info(s"getting depends on ${addedPackage.name}")
    val validCondPackList = F
      .toIO(spRepo.findByDeps(addedPackage.name))
      .unsafeRunSync()
      .filter(
        _.deps
          .get(addedPackage.name)
          .exists(condition =>
            Try { Range(condition.replace("^latest$", "*")).valid(addedPackage.version) }.getOrElse(false)
          )
      )
    logger.info(s"valid length: ${validCondPackList.size}")

    // 追加されたパッケージのバージョンが指定条件を満たしているものについて、現在依存しているパッケージのバージョンとの比較を行う
    validCondPackList.foreach(pack => {
      val ids = redis.get[String](directedKey(pack.id)).map(splitRedisData(_))
      if (ids.exists(_.nonEmpty)) {
        val idList = ids.get
        val directed = F.toIO(spRepo.findByIds(idList)).unsafeRunSync()
        val v = directed.filter(p => p.name == addedPackage.name && p.version < addedPackage.version).headOption
        if (v.isDefined) {
          // logger.info(s"update ${pack.name}@${pack.version.original}")
          val newList = idList.filterNot(x => x == v.get.id) :+ addedPackage.id
          updatedPackDirectlyDepsMap.update(pack.id, newList.toSet)
        }
      }
    })
    logger.info(s"update ${updatedPackDirectlyDepsMap.size} package")
    val allValidId =
      redis.get[String](allIdSetKey).map(x => splitRedisData(x)).getOrElse(Seq.empty[Int]) :+ id
    redis.set(allIdSetKey, allValidId.mkString(","))
    if (updatedPackDirectlyDepsMap.isEmpty) {
      // 追加されたパッケージによって変更されたパッケージがない場合は、追加されたパッケージについてだけ計算すればいい
      logger.info("save the set of pacakges only about added package")
      addedDirectlyDeps.foreach(v =>
        addedIndirectlyDeps ++= redis.get[String](packagesKey(v)).map(splitRedisData(_)).getOrElse(Set.empty)
      )
      if (addedDirectlyDeps.size > 1) redis.set(directedKey(addedPackage.id), addedDirectlyDeps.mkString(","))
      redis.set(packagesKey(addedPackage.id), addedIndirectlyDeps.mkString(","))
    } else {
      logger.info("calcurate all the set of indirectly-depending packages...")
      val allDirectlyDependecies =
        allValidId
          .grouped(100)
          .map(v => {
            redis
              .mget[String](directedKey(v.head), v.tail.map(directedKey(_)): _*)
              .map(_.zipWithIndex.map(x => (v(x._2), x._1.map(v => splitRedisData(v).toSet).getOrElse(Set.empty))))
              .get
          })
          .flatten
          .toMap[Int, Set[Int]]

      val allIndirectDepMap =
        allValidId.map(x => (x, scala.collection.mutable.Set.empty[Int])).toMap[Int, scala.collection.mutable.Set[Int]]
      var complete = false
      var updated_pre = Set.empty[Int]
      while (!complete) {
        // var updatecount = 0
        complete = true
        var pack_count_in_loop = 0
        val updated = scala.collection.mutable.Set.empty[Int]
        allValidId.foreach(id => {
          if (pack_count_in_loop % 1000000 == 0) logger.info(s"${pack_count_in_loop}, ${updated.size}")
          val directly =
            updatedPackDirectlyDepsMap.get(id).getOrElse(allDirectlyDependecies.get(id).getOrElse(Set.empty))
          val size = allIndirectDepMap(id).size
          if (updated_pre.isEmpty) {
            allIndirectDepMap(id) ++= directly
          } else {
            directly.foreach(d_id => {
              if (updated_pre.contains(d_id)) {
                allIndirectDepMap(id) ++= allIndirectDepMap(d_id)
              }
            })
          }
          if (allIndirectDepMap(id).size > size) {
            updated += id
          }
          pack_count_in_loop += 1
        })
        if (updated.nonEmpty) {
          updated_pre = updated.toSet
          logger.info(s"updated: ${updated_pre.size}")
          complete = false
        }
      }
      logger.info(s"calcurate complete!")
      // redis.mset(updatedPackIndirectlyDepsMap.map(v => (directedKey(v._1), v._2.mkString(","))).toSeq: _*)
      logger.info(s"save about indirectly dep map")
      System.gc()
      allIndirectDepMap.toSeq
        .map(v => (packagesKey(v._1), v._2.mkString(",")))
        .grouped(100)
        .zipWithIndex
        .foreach(v => {
          if (v._2 * 100 % 1000000 == 0) {
            logger.info(s"${v._2}")
            System.gc()
          }
          redis.mset(v._1: _*)
        })
      redis.mset(updatedPackDirectlyDepsMap.map(v => (directedKey(v._1), v._2.mkString(","))).toSeq: _*)
      logger.info("complete save data!")
    }
    ()
  }

  private def splitRedisData(str: String): Seq[Int] = str.split(",").map(_.toIntOption).flatten.toSeq

  private def directedKey(id: Int) = s"directed_${id}"
  private def packagesKey(id: Int) = s"packages_${id}"

  private def allIdSetKey = "all_id_set"

  implicit class IntOption(src: String) {
    def toIntOption: Option[Int] = Try { Some(src.toInt) }.getOrElse(None)
  }
}
