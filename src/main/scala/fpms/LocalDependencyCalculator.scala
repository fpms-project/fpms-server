package fpms

import org.slf4j.LoggerFactory
import com.github.sh4869.semver_parser.Range
import fpms.json.JsonLoader
import scala.util.Try
import fpms.SourcePackageInfo._
import io.circe.Json

class LocalDependencyCalculator extends DependencyCalculator {
  private lazy val logger = LoggerFactory.getLogger(this.getClass)
  private val internalMap = scala.collection.mutable.Map.empty[Int, PackageNode]

  def initialize(): Unit = {
    setup()
    algo()
    ()
  }

  def get(id: Int): Option[PackageNode] = internalMap.get(id)

  /**
    * WARNING: same as initilalize
    */
  def load(): Unit = initialize()

  def add(added: Seq[SourcePackageInfo]): Unit = {}

  private def setup(): Unit = {
    val packs_map = JsonLoader.createMap()
    logger.info("complete convert to list")
    var depCache = scala.collection.mutable.Map.empty[(String, String), Int]
    val packs_map_array = packs_map.values.toArray
    logger.info(s"pack_array_length : ${packs_map_array.size}")
    for (i <- 0 to packs_map_array.length - 1) {
      if (i % 100000 == 0) logger.info(s"count: ${i}, length: ${internalMap.size}")
      val a = packs_map_array(i)
      for (j <- 0 to a.length - 1) {
        val pack = a(j)
        val id = pack.id
        if (pack.deps.isEmpty) {
          internalMap.update(id, PackageNode(id, Seq.empty, scala.collection.mutable.Set.empty))
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
            internalMap.update(id, PackageNode(id, depsx.toArray.toSeq, scala.collection.mutable.Set.empty))
          }
        }
      }
    }
    logger.info("setup complete!")
    println("setup complete")
  }

  private def algo() {
    System.gc()
    logger.info(s"get count : ${internalMap.size}")
    logger.info("start loop")
    var count = 0
    val maps = internalMap.values.toArray
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
              val tar = internalMap.get(d)
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
}
