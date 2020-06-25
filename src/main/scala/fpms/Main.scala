package fpms

import cats.effect.concurrent.MVar
import cats.effect.concurrent.Semaphore
import com.gilt.gfc.semver.SemVer
import fpms.repository.memory.{PackageAllDepMemoryRepository, PackageDepRelationMemoryRepository}
import fpms.repository.memoryexception.PackageInfoMemoryRepository
import fpms.util.JsonLoader
import org.slf4j.LoggerFactory
import scala.util.control.Breaks
import semver.ranges.Range
import scala.util.Try
object Fpms {
  private val logger = LoggerFactory.getLogger(this.getClass)
  private val idmap = scala.collection.mutable.Map.empty[String, Int]
  def pack_to_string(pack: PackageInfo) = s"${pack.name}@${pack.version.toString()}"
  private var id = 0

  def main(args: Array[String]) {
    logger.info(s"${Runtime.getRuntime().maxMemory()}")
    logger.info("start log!")
    val map = setup()
    algo(map)
  }

  def add_id(pack: PackageInfo): Unit = {
    idmap.update(pack_to_string(pack), id)
    id += 1
  }

  def get_id(pack: PackageInfo): Int = idmap.get(pack_to_string(pack)).getOrElse(-1)

  def setup(): Map[Int, PackageNode] = {
    val packs = JsonLoader.createLists()
    val packs_map = scala.collection.mutable.Map.empty[String, Seq[PackageInfo]]
    // packs_map.sizeHint(packs.size)
    for (i <- 0 to packs.size - 1) {
      if (i % 100000 == 0) logger.info(s"$i")
      val pack = packs(i)
      val seq = scala.collection.mutable.ArrayBuffer.empty[PackageInfo]
      for (j <- 0 to pack.versions.size - 1) {
        val d = pack.versions(j)
        try {
          val info = PackageInfo(pack.name, d.version, d.dep)
          seq += info
          add_id(info)
        } catch {
          case _: Throwable => Unit
        }
      }
      packs_map += (pack.name -> seq.toSeq)
    }
    var depCache = scala.collection.mutable.Map.empty[(String, String), Int]
    val packs_map_array = packs_map.values.toArray
    val map = scala.collection.mutable.Map.empty[Int, PackageNode]
    var hit = 0
    var miss = 0
    logger.info(s"pack_array_length : ${packs_map_array.size}")
    for (i <- 0 to packs_map_array.length - 1) {
      if (i % 100000 == 0) logger.info(s"count: ${i}, length: ${map.size}, hit ${hit} / miss ${miss}")
      val a = packs_map_array(i)
      for (j <- 0 to a.length - 1) {
        val pack = a(j)
        val id = get_id(pack)
        try {
          if (pack.dep.isEmpty) {
            map.update(id, PackageNode(id, Seq.empty, scala.collection.mutable.Set.empty))
          } else {
            val depsx = scala.collection.mutable.ArrayBuffer.empty[Int]
            depsx.sizeHint(pack.dep.size)
            var failed = false
            var k = pack.dep.size - 1
            val seq = pack.dep.toSeq
            while (!failed && k > -1) {
              val d = seq(k)
              val cache = depCache.get(d)
              if (cache.isEmpty) {
                miss += 1
                var depP = for {
                  ds <- packs_map.get(d._1)
                  depP <- latestP(ds, d._2)
                } yield depP
                depP match {
                  case Some(v) => {
                    val id = get_id(v)
                    depsx += id
                    depCache.update(d, id)
                  }
                  case None => failed = true
                }
              } else {
                hit += 1
                depsx += cache.get
              }
              k -= 1
            }
            if (!failed) {
              map.update(id, PackageNode(id, depsx.toArray.toSeq, scala.collection.mutable.Set.empty))
            }
          }
        } catch {
          case e: Throwable => {
            logger.error(s"${e.getStackTrace().mkString("\n")}")
          }
        }
      }
    }
    logger.info("setup complete!")
    println("setup complete")
    map.toMap
  }

  def algo(map: Map[Int, PackageNode]) {
    System.gc()
    logger.info(s"get count : ${map.size}")
    logger.info("start loop")
    var count = 0
    val maps = map.values.toArray
    var set = scala.collection.mutable.TreeSet.empty[Int]
    var complete = false
    var first = true
    while (!complete) {
      val check = set.toSet
      set.clear()
      complete = true
      var total = 0
      var x = 0
      for (i <- 0 to maps.size - 1) {
        if (i % 1000000 == 0) logger.info(s"count $count , $i, total | $total")
        val node = maps(i)
        val deps = node.directed
        // 依存関係がない or 前回から変わっていない場合は無視
        if (deps.size == 0) {
          x += 1
        } else {
          var current = node.packages.size
          node.packages ++= node.directed.toSet
          for (j <- 0 to deps.size - 1) {
            val d = deps(j)
            // 更新されたやつだけ追加
            if (first || check.contains(d)) {
              val tar = map.get(d)
              if (tar.isDefined) {
                node.packages ++= tar.get.packages
              }
            }
          }
          total += node.packages.size
          if (node.packages.size != current) {
            complete = false
            set += node.src
          } else {
            x += 1
          }
        }
      }
      logger.info(s"count :$count, x: ${x}, total: $total")
      count += 1
      first = false
    }
    logger.info("complete!")
  }

  import fpms.VersionCondition._
  def latestP(vers: Seq[PackageInfo], condition: String): Option[PackageInfo] = {
    Try {
      val range = Range.valueOf(condition)
      for (i <- vers.length - 1 to 0 by -1) {
        if (range.satisfies(vers(i).version)) {
          return Some(vers(i))
        }
      }
      None
    }.getOrElse(None)
  }

  case class PackageNode(
      src: Int,
      directed: Seq[Int],
      packages: scala.collection.mutable.Set[Int]
  )
}
