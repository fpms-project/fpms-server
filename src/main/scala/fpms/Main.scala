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
object Main {
  private val logger = LoggerFactory.getLogger(this.getClass)
  def main(args: Array[String]) {
    logger.info("start log!")
    val packs = JsonLoader.createLists()
    println("json loaded")
    logger.info("json loaded!")
    val map = setup(packs)
    algo(map)
  }

  def setup(packs: Array[RootInterface]): Map[PackageInfoBase, PackageNode] = {
    val pack_convert = scala.collection.mutable.ArrayBuffer.empty[PackageInfo]
    val packs_map = scala.collection.mutable.Map.empty[String, Seq[PackageInfo]]
    packs_map.sizeHint(packs.size)
    // あんまよくない
    pack_convert.sizeHint(13300000)
    for (i <- 0 to packs.size - 1) {
      if (i % 100000 == 0) {
        System.gc()
        logger.info(s"$i")
      }
      val pack = packs(i)
      val seq = scala.collection.mutable.ArrayBuffer.empty[PackageInfo]
      for (j <- 0 to pack.versions.size - 1) {
        val d = pack.versions(j)
        try {
          val info = PackageInfo(pack.name, d.version, d.dep)
          pack_convert += info
          seq += info
        } catch {
          case _: Throwable => Unit
        }
      }
      packs_map += (pack.name -> seq.toSeq)
    }
    val pack_convert_array = pack_convert.toArray
    val packs_map_imu = packs_map.toMap
    val map = scala.collection.mutable.Map.empty[PackageInfoBase, PackageNode]
    for (i <- 0 to pack_convert_array.length - 1) {
      if (i % 100000 == 0) {
        logger.info(s"count: ${i}, length: ${map.size}")
        System.gc()
      }
      val pack = pack_convert_array(i)
      try {
        if (pack.dep.isEmpty) {
          map.update(pack.base, PackageNode(pack, Seq.empty, false, scala.collection.mutable.Map.empty))
        } else {
          val depsx = scala.collection.mutable.ArrayBuffer.empty[PackageInfoBase]
          depsx.sizeHint(pack.dep.size)
          var failed = false
          var j = pack.dep.size - 1
          val seq = pack.dep.toSeq
          while (!failed && j > -1) {
            val d = seq(j)
            var depP = for {
              ds <- packs_map_imu.get(d._1)
              depP <- latestP(ds, d._2)
            } yield depP
            depP match {
              case Some(v) => depsx += v.base
              case None    => failed = true
            }
            j -= 1
          }
          if (!failed) {
            map.update(
              pack.base,
              PackageNode(pack, depsx.toArray.toSeq, true, scala.collection.mutable.Map.empty)
            )
          }
        }
      } catch {
        case e: Throwable => {
          logger.error(s"${e.getStackTrace().mkString("\n")}")
        }
      }
    }
    logger.info("setup complete!")
    println("setup complete")
    map.toMap
  }

  def algo(map: Map[PackageInfoBase, PackageNode]) {
    logger.info(s"get count : ${map.size}")
    logger.info("start loop")
    var count = 0
    val maps = map.values.toArray
    var complete = false
    while (!complete) {
      complete = true
      var x = 0
      var skip = 0
      for (i <- 0 to maps.size - 1) {
        if (i % 100000 == 0) {
          logger.info(s"count $count , $i")
          System.gc()
        }
        val node = maps(i)
        val deps = node.directed
        // 依存関係がない or 前回から変わっていない場合は無視
        if (deps.size == 0) {
          x += 1
        } else if (!node.changeFromBefore) {
          x += 1
          skip += 1
        } else {
          var change = false
          for (j <- 0 to deps.size - 1) {
            val d = deps(j)
            val tar = map.get(d)
            if (tar.isEmpty) {
              node.packages.update(d, Set.empty)
            } else {
              val arr = scala.collection.mutable.Set.empty[PackageInfoBase]
              val seqs = tar.get.packages.toArray
              for (k <- 0 to seqs.size - 1) {
                val t = seqs(k)
                arr += t._1
                arr ++= t._2
              }
              val deparra = arr.toSet
              if (node.packages.get(d).forall(z => z != deparra)) change = true
              node.packages.update(d, deparra)
            }
          }
          node.changeFromBefore = change
          if (change) complete = false
          else x += 1
        }
      }
      logger.info(s"count :$count, x: ${x}, skip: $skip")
      count += 1
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
      src: PackageInfo,
      directed: Seq[PackageInfoBase],
      var changeFromBefore: Boolean,
      packages: scala.collection.mutable.Map[PackageInfoBase, Set[PackageInfoBase]]
  )
  /*

  def temp: IO[ExitCode] = {
    logger.info("start log!")
    val packs = JsonLoader.createLists()
    logger.info("json loaded!")
    for {
      repos <- getRepositories()
      _ <- new PackageRegisterer[IO](repos._1, repos._2, repos._3, packs).registerPackages()
    } yield ExitCode.Success
  }

  def getRepositories(): IO[
    (
        PackageInfoMemoryRepository[IO],
        PackageDepRelationMemoryRepository[IO],
        PackageAllDepMemoryRepository[IO]
    )
  ] = {
    for {
      c <- for {
        c <- MVar.of[IO, Map[String, Seq[String]]](Map.empty)
        d <- MVar.of[IO, Map[PackageInfoBase, PackageInfo]](Map.empty)
      } yield new PackageInfoMemoryRepository[IO](c, d)
      a <- for {
        a <- MVar.of[IO, Map[PackageInfoBase, Map[String, Seq[PackageInfoBase]]]](Map.empty)
      } yield new PackageAllDepMemoryRepository[IO](a)
      b <- for {
        b <- MVar.of[IO, Map[String, Seq[PackageInfoBase]]](Map.empty)
      } yield new PackageDepRelationMemoryRepository[IO](b)
    } yield (c, b, a)
  }
   */
}
