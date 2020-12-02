package fpms.calcurator

import com.typesafe.scalalogging.LazyLogging

import fpms.LibraryPackage
import fpms.json.JsonLoader

import LDILMapGenerator._

/**
  * JSONデータからPackgeNodeのMapを作る
  * TODO: JSONからしか読み込むことができないため、ファイルの追加等はできない
  */
object JsonLDILMapGenerator extends LazyLogging {
  def gen: LDILMap = {
    val nameToPacksMap = JsonLoader.createNamePackagesMap()
    val packsGroupedByName: List[List[LibraryPackage]] = nameToPacksMap.values.toList.map(_.toList)
    val finder = new LatestDependencyFinder(nameToPacksMap.get)
    val map = scala.collection.mutable.Map.empty[Int, List[Int]]
    logger.info(s"number of names of packages : ${packsGroupedByName.size}")
    packsGroupedByName.zipWithIndex.foreach {
      case (v, i) => {
        if (i % 100000 == 0) logger.info(s"count: ${i}, length: ${map.size}")
        v.foreach { pack =>
          try {
            val ids = finder.findIds(pack)
            map.update(pack.id, ids)
          } catch {
            case _: Throwable => ()
          }
        }
      }
    }
    logger.info(s"complete generating id list map - length: ${map.size}")
    map.toMap
  }
}

object LDILMapGenerator {
  type LDILMap = Map[Int, List[Int]]
}
