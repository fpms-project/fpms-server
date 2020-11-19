package fpms.calcurator

import com.typesafe.scalalogging.LazyLogging

import fpms.LibraryPackage
import fpms.json.JsonLoader

import LatestDependencyIdListMapGenerator._

/**
  * ID→そのパッケージが直接依存する依存パッケージ
  */
trait LatestDependencyIdListMapGenerator {
  def gen: LatestDependencyIdListMap
}

/**
  * JSONデータからPackgeNodeのMapを作る
  * TODO: JSONからしか読み込むことができないため、ファイルの追加等はできない
  */
class JsonLatestDependencyIdListMapGenerator extends LatestDependencyIdListMapGenerator with LazyLogging {
  def gen: LatestDependencyIdListMap = {
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

object LatestDependencyIdListMapGenerator {
  type LatestDependencyIdListMap = Map[Int, List[Int]]
}
