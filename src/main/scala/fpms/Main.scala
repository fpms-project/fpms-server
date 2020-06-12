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
    logger.info("json loaded!")
    val pack_convert = scala.collection.mutable.ArrayBuffer.empty[PackageInfo]
    val packs_map = scala.collection.mutable.Map.empty[String, Seq[PackageInfo]]
    packs_map.sizeHint(packs.size)
    // あんまよくない
    pack_convert.sizeHint(13300000)
    for (i <- 0 to packs.size - 1) {
      if (i % 100000 == 0) logger.info(s"$i")
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
      packs_map += (pack.name -> seq)
    }
    logger.info("call algo")
    algo(pack_convert.toArray, packs_map.toMap)
  }

  def algo(pack_convert: Array[PackageInfo], packs_map: Map[String, Seq[PackageInfo]]) {
    logger.info("call algo")
    val map = scala.collection.mutable.Map.empty[PackageInfo, PackageNode]
    map.sizeHint(pack_convert.length)
    for (i <- 0 to pack_convert.length - 1) {
      if (i % 100000 == 0) {
        System.gc();
        logger.info(s"count: ${i}, length: ${map.size}")
      }
      val pack = pack_convert(i)
      try {
        if (pack.dep.isEmpty) {
          map.update(pack, PackageNode(pack, Seq.empty, false, scala.collection.mutable.Map.empty))
        } else {
          val depsx = scala.collection.mutable.ArrayBuffer.empty[PackageInfo]
          depsx.sizeHint(pack.dep.size)
          var failed = false
          var j = pack.dep.size - 1
          while (!failed && j > -1) {
            val d = pack.dep.toSeq.apply(j)
            var depP = for {
              ds <- packs_map.get(d._1)
              depP <- latestP(ds, d._2)
            } yield depP
            depP match {
              case Some(v) => depsx += v
              case None    => failed = true
            }
            j -= 1
          }
          if (!failed) {
            map.update(
              pack,
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
    logger.info(s"get count : ${map.size}")
    logger.info("start loop")
    var count = 0

    val maps = map.values.toArray
    val arr = scala.collection.mutable.Set.empty[PackageInfo]
    var complete = false
    while (!complete) {
      complete = true
      var x = 0
      System.gc()
      for (i <- 0 to maps.size - 1) {
        if (i % 100000 == 0) logger.info(s"count $count , $i")
        val node = maps(i)
        val deps = node.directed
        // 依存関係がない場合無視
        if (deps.size == 0) x += 1
        else {
          var change = false
          for (j <- 0 to deps.size - 1) {
            val d = deps(j)
            val tar = map.get(d)
            if (tar.isEmpty) {
              node.packages.update(d, Set.empty)
            } else {
              val seqs = tar.get.packages.toArray
              for (k <- 0 to seqs.size - 1) {
                val t = seqs(k)
                arr += t._1
                arr ++= t._2
              }
              val deparra = arr.toSet
              if (node.packages.get(d).forall(z => z != deparra)) change = true
              node.packages.update(d, deparra)
              arr.clear()
            }
          }
          map.update(node.src, node.copy(changeFromBefore = change))
          if (change) complete = false
          else x += 1
        }
      }
      logger.info(s"count :$count, x: ${x}")
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
      directed: Seq[PackageInfo],
      changeFromBefore: Boolean,
      packages: scala.collection.mutable.Map[PackageInfo, Set[PackageInfo]]
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
