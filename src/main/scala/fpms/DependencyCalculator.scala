package fpms

import org.slf4j.LoggerFactory
import com.github.sh4869.semver_parser.Range
import fpms.json.JsonLoader
import scala.util.Try

object DependencyCalculator {
  private val logger = LoggerFactory.getLogger(this.getClass)

  def inialize(): Map[Int, PackageNode] = {
    val map = setup()
    algo(map)
    map
  }

  private def setup(): Map[Int, PackageNode] = {
    val packs = JsonLoader.loadIdList()
    val packs_map = scala.collection.mutable.Map.empty[String, Seq[SourcePackageInfo]]
    for (i <- 0 to packs.size - 1) {
      if (i % 100000 == 0) logger.info(s"convert to List: $i")
      val pack = packs(i)
      val seq = scala.collection.mutable.ArrayBuffer.empty[SourcePackageInfo]
      for (j <- 0 to pack.versions.size - 1) {
        val d = pack.versions(j)
        try {
          val info = SourcePackageInfo(pack.name, d.version, d.dep, d.id)
          seq += info
        } catch {
          case _: Throwable => Unit
        }
      }
      packs_map += (pack.name -> seq.toSeq)
    }
    logger.info("complete convert to list")
    var depCache = scala.collection.mutable.Map.empty[(String, String), Int]
    val packs_map_array = packs_map.values.toArray
    val map = scala.collection.mutable.Map.empty[Int, PackageNode]
    logger.info(s"pack_array_length : ${packs_map_array.size}")
    for (i <- 0 to packs_map_array.length - 1) {
      if (i % 100000 == 0) logger.info(s"count: ${i}, length: ${map.size}")
      val a = packs_map_array(i)
      for (j <- 0 to a.length - 1) {
        val pack = a(j)
        val id = pack.id
        try {
          if (pack.deps.isEmpty) {
            map.update(id, PackageNode(id, Seq.empty, scala.collection.mutable.Set.empty))
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
                  depP <- latestP(ds, d._2)
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
              map.update(id, PackageNode(id, depsx.toArray.toSeq, scala.collection.mutable.Set.empty))
            }
          }
        } catch {
          case e: Throwable => logger.error(s"${e.getStackTrace().mkString("\n")}")
        }
      }
    }
    logger.info("setup complete!")
    println("setup complete")
    map.toMap
  }

  private def algo(map: Map[Int, PackageNode]) {
    System.gc()
    logger.info(s"get count : ${map.size}")
    logger.info("start loop")
    var count = 0
    val maps = map.values.toArray
    var updateIdSets = scala.collection.mutable.TreeSet.empty[Int]
    var complete = false
    var first = true
    while (!complete) {
      logger.info(s"start   lap ${count}")
      val updated = updateIdSets.toSet
      updateIdSets.clear()
      complete = true
      var node_update_count = 0
      for (i <- 0 to maps.size - 1) {
        if (i % 1000000 == 0) logger.info(s"$i")
        val node = maps(i)
        val deps = node.directed
        // 依存関係がない場合は無視
        if (deps.size == 0) node_update_count += 1
        else {
          var currentSize = node.packages.size
          node.packages ++= node.directed.toSet
          for (j <- 0 to deps.size - 1) {
            val d = deps(j)
            // 更新されたやつだけ追加
            if (first || updated.contains(d)) {
              val tar = map.get(d)
              if (tar.isDefined) {
                node.packages ++= tar.get.packages
              }
            }
          }
          if (node.packages.size != currentSize) {
            complete = false
            updateIdSets += node.src
          } else {
            node_update_count += 1
          }
        }
      }
      logger.info(s"complete lap ${count}, not updated: ${node_update_count}")
      count += 1
      first = false
    }
    logger.info("complete!")
  }

  private def latestP(vers: Seq[SourcePackageInfo], condition: String): Option[SourcePackageInfo] = {
    Try {
      val range = Range(condition)
      for (i <- vers.length - 1 to 0 by -1) {
        if (range.valid(vers(i).version)) {
          return Some(vers(i))
        }
      }
      None
    }.getOrElse(None)
  }

}

case class PackageNode(
    src: Int,
    directed: Seq[Int],
    packages: scala.collection.mutable.Set[Int]
)
