package fpms

import com.redis.RedisClient
import com.redis.serialization.Parse.Implicits._
import fpms.repository.SourcePackageRepository
import fpms.json.JsonLoader
import org.slf4j.LoggerFactory
import scala.util.Try

class RedisDependecyCalculator[F[_]](redis: RedisClient, spRepo: SourcePackageRepository[F])
    extends DependencyCalculator {

  private val logger = LoggerFactory.getLogger(this.getClass)
  private var validId = scala.collection.mutable.Set.empty[Int]
  private val directedCache = scala.collection.mutable.Map.empty[Int, Seq[Int]]

  def initialize(): Unit = {
    redis.flushall
    initializeList()
    algo()
  }

  private def initializeList() {
    val packs_map = JsonLoader.createMap()
    var depCache = scala.collection.mutable.Map.empty[(String, String), Int]
    val packs_map_array = packs_map.values.toArray
    logger.info(s"pack_array_length : ${packs_map_array.size}")
    for (i <- 0 to packs_map_array.length - 1) {
      if (i % 1000000 == 0) logger.info(s"count: ${i}, length: ${validId.size}")
      val a = packs_map_array(i)
      for (j <- 0 to a.length - 1) {
        val pack = a(j)
        val id = pack.id
        if (pack.deps.isEmpty) {
          // 自分自身だけ追加しておく
          redis.sadd(packagesKey(pack.id), pack.id)
          validId += pack.id
          directedCache += (pack.id -> Seq.empty)
        } else {
          val depsx = scala.collection.mutable.ArrayBuffer.empty[Int]
          depsx.sizeHint(pack.deps.size)
          var failed = false
          var k = pack.deps.size - 1
          val seq = pack.deps.toSeq
          while (!failed && k > -1) {
            val d = seq(k)
            val cache = depCache.get(d)
            if (cache.isEmpty) {
              var depP = for {
                ds <- packs_map.get(d._1)
                depP <- ds.latestInFits(d._2)
              } yield depP
              depP match {
                case Some(v) => {
                  depsx += v.id
                  depCache.update(d, v.id)
                }
                case None => failed = true
              }
            } else {
              depsx += cache.get
            }
            k -= 1
          }
          if (!failed) {
            validId += pack.id
            redis.lpush(directedKey(pack.id), depsx.head, depsx.tail: _*)
            directedCache += (pack.id -> depsx.toSeq)
            redis.sadd(packagesKey(pack.id), pack.id, depsx.toSeq: _*)
          }
        }
      }
    }
    logger.info("setup complete!")
    println("setup complete")
  }

  private def algo() {
    var count = 0
    var complete = false
    var checkSet = Set.empty[Int]
    val validIdSeq = validId.toSeq
    while (!complete) {
      logger.info(s"start   lap ${count}")
      val updated = scala.collection.mutable.Set.empty[Int]
      complete = true
      for (i <- 0 to validIdSeq.length - 1) {
        if (i % 100000 == 0) logger.info(s"$i")
        val id = validIdSeq(i)
        val directed = getDirected(id)
        val sets = scala.collection.mutable.Set.empty[Int]
        directed.foreach(dId => {
          if (count == 0 || checkSet.contains(dId)) {
            redis
              .smembers[Int](packagesKey(dId))
              .map(v => {
                sets ++= v.flatten
              })
          }
        })
        if (sets.nonEmpty) {
          val added = redis.sadd(packagesKey(id), id, sets.toSeq: _*)
          if (added.exists(_ > 0)) {
            updated += id
            complete = false
          }
        }
      }
      logger.info(s"complete lap ${count}, updated: ${updated.size}")
      checkSet = updated.toSet
      count += 1
    }
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

  def get(id: Int): Option[PackageNode] = {
    for {
      d <- redis.lrange[Int](directedKey(id), 0, -1)
      p <- redis.smembers[Int](packagesKey(id))
    } yield PackageNode(id, d.flatten, scala.collection.mutable.Set[Int](p.flatten.toSeq: _*))
  }

  def load(): Unit = ???

  def add(added: Seq[SourcePackageInfo]): Unit = ???

  private def directedKey(id: Int) = s"directed_${id}"
  private def packagesKey(id: Int) = s"packages_${id}"

  implicit class IntOption(src: String) {
    def toIntOption: Option[Int] = Try { Some(src.toInt) }.getOrElse(None)
  }
}
