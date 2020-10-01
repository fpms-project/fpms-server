package fpms

import com.redis.RedisClient
import com.redis.serialization.Parse.Implicits._
import fpms.repository.SourcePackageRepository
import fpms.json.JsonLoader
import org.slf4j.LoggerFactory
import scala.util.Try
import cats.implicits._
import cats.effect.ConcurrentEffect
import com.github.sh4869.semver_parser.{Range, SemVer}

class RedisDependecyCalculator[F[_]](redis: RedisClient, spRepo: SourcePackageRepository[F])(
    implicit F: ConcurrentEffect[F]
) extends DependencyCalculator {

  private val logger = LoggerFactory.getLogger(this.getClass)
  private val directedCache = scala.collection.mutable.Map.empty[Int, Seq[Int]]

  def initialize(): Unit = {
    redis.flushall
    val x = new LocalDependencyCalculator
    x.initialize()
    saveInitializeList(x.getMap)
  }

  private def saveInitializeList(map: Map[Int, PackageNode]) {
    map.foreach(v => {
      val id = v._1
      redis.sadd(allIdSetKey, id, Seq.empty);
      if (v._2.directed.size > 1) {
        redis.lpush(directedKey(id), v._2.directed.head, v._2.directed.tail: _*)
      } else if (v._2.directed.size == 1) {
        redis.lpush(directedKey(id), v._2.directed.head)
      }
      redis.sadd(packagesKey(id), id, v._2.packages.toSeq: _*)
    })
  }

  def get(id: Int): Option[PackageNode] = {
    for {
      d <- redis.lrange[Int](directedKey(id), 0, -1)
      p <- redis.smembers[Int](packagesKey(id))
    } yield PackageNode(id, d.flatten, scala.collection.mutable.Set[Int](p.flatten.toSeq: _*))
  }

  def load(): Unit = ???

  def add(added: AddPackage): Unit = {
    import fpms.SourcePackage._
    val directedUpdated = scala.collection.mutable.Set.empty[Int]
    val id = F.toIO(spRepo.getMaxId()).unsafeRunSync() + 1
    val directly = scala.collection.mutable.Set.empty[SourcePackage]
    var error = false
    added.deps.map(v => {
      val targets = F.toIO(spRepo.findByName(v._1)).unsafeRunSync()
      targets.toSeq.latestInFits(v._2) match {
        case Some(value) => directly.add(value)
        case None        => error = true
      }
    })
    if (error) {
      logger.info("error: cannot get latest")
      return ()
    }
    val target = SourcePackage(added.name, SemVer(added.version), added.deps, id)
    F.toIO(spRepo.insert(target)).unsafeRunSync()
    logger.info(s"added package: ${target.name}@${target.version.original}")

    val targetDirectly = directly.map(_.id)
    val targetAllMap = scala.collection.mutable.Set(directly.map(_.id).toSeq :+ target.id: _*)
    val updateDirectlyMap = scala.collection.mutable.Map.empty[Int, Seq[Int]]
    val updateAllMap = scala.collection.mutable.Map.empty[Int, Set[Int]]

    logger.info(s"getting depends on ${target.name}")
    val dependes = F.toIO(spRepo.findByDeps(target.name)).unsafeRunSync()
    logger.info(s"getted depends on ${target.name}")
    val validList = dependes.filter(v =>
      v.deps
        .get(target.name)
        .exists(condition => Try { Range(condition.replace("^latest$", "*")).valid(target.version) }.getOrElse(false))
    )
    logger.info(s"valid length: ${validList.size}")
    validList.foreach(pack => {
      val ids = redis.lrange[Int](directedKey(pack.id), 0, -1)
      if (ids.exists(_.nonEmpty)) {
        val idList = ids.get.flatten
        val directed = F.toIO(spRepo.findByIds(idList.toNel.get)).unsafeRunSync()
        val v = directed.filter(p => p.name == target.name && p.version < target.version).headOption
        if (v.isDefined) {
          logger.info(s"updated directly package: ${pack.name}@${pack.version.original}")
          val newList = idList.filterNot(x => x == v.get.id) :+ target.id
          updateDirectlyMap.update(pack.id, newList)
          updateAllMap.update(pack.id, (newList :+ pack.id).toSet)
        }
      }
    })
    logger.info(s"update ${updateDirectlyMap.size} package")
    // 追加されたパッケージによって変更されたパッケージがない場合は、追加されたパッケージについてだけ計算すればいい
    if (updateDirectlyMap.isEmpty) {
      logger.info("save only once")
      targetDirectly.foreach(v => {
        val x = redis.smembers[Int](packagesKey(v))
        targetAllMap ++= x.map(_.flatten).getOrElse(Set.empty)
      })
      if (targetDirectly.size > 1) {
        redis.lpush(directedKey(target.id), targetDirectly.head, targetDirectly.tail.toSeq: _*)
      } else if (targetDirectly.size == 1) {
        redis.lpush(directedKey(target.id), targetDirectly.head)
      }
      redis.sadd(packagesKey(target.id), target.id, targetAllMap.toSeq: _*)
    } else {
      logger.info("calcurate program")
      val allValidId = redis.get[String](allIdSetKey).map(_.split(",").toSeq.map(_.toIntOption).flatten).getOrElse(Seq.empty[Int])
      logger.info("??")
      var complete = false
      while (!complete) {
        var updatecount = 0
        complete = true
        var count = 0
        allValidId.foreach(id => {
          if (count % 10000 == 0) logger.info(s"${count}")
          val directly = updateDirectlyMap.get(id).getOrElse(getDirected(id))
          updateAllMap.get(id) match {
            case Some(value) => {
              val newMap = scala.collection.mutable.Set.empty[Int]
              directly.map(direcly_p_id => {
                val v = updateAllMap
                  .get(direcly_p_id)
                  .getOrElse(redis.smembers[Int](packagesKey(direcly_p_id)).map(_.flatten).get)
                newMap ++= v
              })
              if (newMap.size > value.size) {
                updatecount += 1
                updateAllMap.update(id, newMap.toSet)
              }
            }
            case None => {
              if (updateAllMap.find(v => directly.contains(v._1)).isDefined) {
                val newMap = scala.collection.mutable.Set.empty[Int]
                directly.map(direcly_p_id => {
                  val v = updateAllMap
                    .get(direcly_p_id)
                    .getOrElse(redis.smembers[Int](packagesKey(direcly_p_id)).map(_.flatten).get)
                  newMap ++= v
                })
                updatecount += 1
                updateAllMap.update(id, newMap.toSet)
              }
            }
          }
          count += 1
        })
        if (updatecount > 0) {
          logger.info(s"updated: $updatecount")
          complete = false
        }
      }
      logger.info("calcurate complete")
      updateAllMap.foreach(v => {
        redis.del(packagesKey(v._1))
        redis.sadd(packagesKey(v._1), v._1, v._2.toSeq: _*)
      })
      updateDirectlyMap.foreach(v => {
        redis.del(directedKey(v._1))
        if (v._2.size > 1) {
          redis.lpush(directedKey(v._1), v._2.head, v._2.tail: _*)
        } else if (v._2.size == 1) {
          redis.lpush(directedKey(v._1), v._2.head)
        }
      })
    }
    ()
    // spRepo.findByDeps() であるパッケージに依存しているパッケージを全部取得
    // その中で新しいバージョンに適合するものだけを取得
    // redisに問い合わせをして、今あるバージョンより新しければredisにdirected_x_new を追加してそれを計算するように
  }

  private def getDirected(id: Int): Seq[Int] = {
    directedCache.get(id) match {
      case Some(value) => value
      case None => {
        val get = redis.lrange[Int](directedKey(id), 0, -1)
        get.fold(Seq.empty[Int])(v => {
          val result = v.flatten.toSeq
          directedCache += (id -> result)
          result
        })
      }
    }
  }

  private def directedKey(id: Int) = s"directed_${id}"
  private def packagesKey(id: Int) = s"packages_${id}"

  private def allIdSetKey = "all_id_set"

  implicit class IntOption(src: String) {
    def toIntOption: Option[Int] = Try { Some(src.toInt) }.getOrElse(None)
  }
}
