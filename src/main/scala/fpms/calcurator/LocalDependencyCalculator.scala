package fpms.calcurator

import org.slf4j.LoggerFactory

import fpms.calcurator.VersionFinder._
import fpms.json.JsonLoader

class LocalDependencyCalculator extends DependencyCalculator {
  private lazy val logger = LoggerFactory.getLogger(this.getClass)
  private val internalMap = scala.collection.mutable.Map.empty[Int, PackageNode]

  def initialize(): Unit = {
    setup()
    algo()
  }

  def getMap = internalMap.toMap

  def get(id: Int): Option[PackageNode] = internalMap.get(id)

  /**
    * WARNING: same as initilalize
    */
  def load(): Unit = initialize()

  def add(added: AddPackage): Unit = {}

  private def setup(): Unit = {
    logger.info("start setup")
    val packs_map = JsonLoader.createMap()
    logger.info("complete convert to list")
    val depCache = scala.collection.mutable.Map.empty[(String, String), Int]
    val packs_map_array = packs_map.values.toArray
    logger.info(s"pack_array_length : ${packs_map_array.size}")
    var all_count = 0
    for (i <- 0 to packs_map_array.length - 1) {
      if (i % 100000 == 0) logger.info(s"count: ${i}, length: ${internalMap.size}")
      val a = packs_map_array(i)
      all_count += a.length
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
              val depP = for {
                ds <- packs_map.get(d._1)
                depP <- ds.latestInFits(d._2.replace("^latest$", "*"))
              } yield depP
              depP match {
                case Some(v) => {
                  depsx += v.id
                  depCache.update(d, v.id)
                }
                case None => {
                  failed = true
                }
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
    logger.info(s"setup complete! ${internalMap.size}  - ${all_count}")
    println("setup complete")
  }

  private def algo() {
    System.gc()
    logger.info(s"get count : ${internalMap.size}")
    logger.info("start loop")
    var count = 0
    val maps = internalMap.values.toArray
    val updateIdSets = scala.collection.mutable.TreeSet.empty[Int]
    var complete = false
    while (!complete) {
      logger.info(s"start   lap ${count}")
      val updated = updateIdSets.toSet
      updateIdSets.clear()
      complete = true
      var updated_count = 0
      for (i <- 0 to maps.size - 1) {
        if (i % 1000000 == 0) logger.info(s"$i")
        val node = maps(i)
        val deps = node.directed
        // 依存関係がない場合は無視
        if (deps.size != 0) {
          val currentSize = node.packages.size
          if (count == 0) node.packages ++= node.directed
          for (j <- 0 to deps.size - 1) {
            val d = deps(j)
            // 更新されたやつだけ追加
            if (count == 0 || updated.contains(d)) {
              val tar = internalMap.get(d)
              if (tar.isDefined) {
                node.packages ++= tar.get.packages
              }
            }
          }
          if (node.packages.size > currentSize) {
            updated_count += 1
            complete = false
            updateIdSets += node.src
          }
        }
      }
      logger.info(s"complete lap ${count}, updated: ${updated_count}")
      count += 1
    }
    logger.info("complete!")
  }
}
