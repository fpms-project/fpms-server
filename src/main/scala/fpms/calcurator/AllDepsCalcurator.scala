package fpms.calcurator

import com.typesafe.scalalogging.LazyLogging

import LatestDependencyIdListMapGenerator.LatestDependencyIdListMap
import AllDepsCalcurator._

class AllDepsCalcurator extends LazyLogging {

  def calcAllDep(latestDepenencyListMap: LatestDependencyIdListMap): PackageCalcuratedDepsMap = {
    System.gc()
    val allMap: Map[Int, scala.collection.mutable.Set[Int]] =
      latestDepenencyListMap.map(v => (v._1, scala.collection.mutable.Set.empty[Int])).toMap
    var updated = updateMapInfo(latestDepenencyListMap, allMap, Set.empty, true)
    while (updated.nonEmpty) {
      logger.info(s"updated size: ${updated.size}")
      updated = updateMapInfo(latestDepenencyListMap, allMap, updated)
    }
    latestDepenencyListMap
      .map(v => (v._1, PackageCalcuratedDeps(v._2, allMap.get(v._1).map(_.toList).getOrElse(Seq.empty))))
      .toMap[Int, PackageCalcuratedDeps]
  }

  private def updateMapInfo(
      latestDepenencyListMap: LatestDependencyIdListMap,
      allMap: Map[Int, scala.collection.mutable.Set[Int]],
      beforeUpdated: Set[Int],
      first: Boolean = false
  ): Set[Int] = {
    val updated = scala.collection.mutable.Set.empty[Int]
    val checkFunction: (Int => Boolean) = 
      // 一定割合以上なら全部足すことにしてみる
      if (beforeUpdated.size / latestDepenencyListMap.size > 0.5) {
        (_) => true
      } else {
        beforeUpdated.contains
      }
    latestDepenencyListMap.toList.map {
      // TODO: 並列化
      // beforeUpdatedは書き込みなしなのでそのまま使える
      // updatedとallMapは書き込みありなのでスレッドセーフなものに置き換える必要がある
      case (id, deps) => {
        if (deps.size > 0) {
          // 初回は自分の子要素を追加して終わり
          if (first) {
            allMap.get(id).get ++= deps.toSet
            updated += id
          } else {
            val oldSize = allMap.get(id).size
            deps.foreach {tid => 
              if(checkFunction(tid)){
                allMap.get(id).get ++= allMap.get(tid).get
              }
            }
            // oldが存在しなかったらもしくは更新されていたらtrue
            if(oldSize > allMap.get(id).get.size) updated += id
          }
        }
      }
    }
    updated.toSet
  }
}

object AllDepsCalcurator {
  type PackageCalcuratedDepsMap = Map[Int, PackageCalcuratedDeps]
}
