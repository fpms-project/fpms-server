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
  private val validId = scala.collection.mutable.Seq.empty[Int]

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
      if (i % 100000 == 0) logger.info(s"count: ${i}, length: ${}")
      val a = packs_map_array(i)
      for (j <- 0 to a.length - 1) {
        val pack = a(j)
        val id = pack.id
        if (pack.deps.isEmpty) {
          // 自分自身だけ追加しておく
          redis.sadd(packagesKey(pack.id), pack.id)
          validId :+ pack.id
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
            validId :+ pack.id
            redis.lpush(directedKey(pack.id), Seq.empty, depsx.toSeq)
            redis.sadd(packagesKey(pack.id), pack.id, depsx.toSeq)
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
    while (!complete) {
      complete = true
      for (i <- 0 to validId.size - 1) {
        val id = validId(i)
        val directed = redis.lrange[Int](directedKey(id), 0, -1)
        directed.map(v =>
          v.flatten.map(directedId => {
            for {
              members <- redis.smembers[Int](packagesKey(directedId))
              added <- redis.sadd(packagesKey(id), id, members.flatten.seq)
            } yield {
              if (added > 0) complete = false
            }
          })
        )
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
