package fpms.calcurator

import com.typesafe.scalalogging.LazyLogging

import LatestDependencyIdListMapGenerator.LatestDependencyIdListMap
import AllDepsCalcurator._

class AllDepsCalcurator extends LazyLogging {
  private lazy val allMap = scala.collection.mutable.Map.empty[Int, Set[Int]]

  def calcAllDep(latestDepenencyListMap: LatestDependencyIdListMap): PackageCalcuratedDepsMap = {
    var updated = updateMapInfo(latestDepenencyListMap, Set.empty, true)
    while (updated.nonEmpty) {
      logger.info(s"updated size: ${updated.size}")
      updated = updateMapInfo(latestDepenencyListMap, updated)
    }
    latestDepenencyListMap
      .map(v => (v._1, PackageCalcuratedDeps(v._2, allMap.get(v._1).map(_.toList).getOrElse(Seq.empty))))
      .toMap[Int, PackageCalcuratedDeps]
  }

  private def updateMapInfo(
      latestDepenencyListMap: LatestDependencyIdListMap,
      beforeUpdated: Set[Int],
      first: Boolean = false
  ): Set[Int] = {
    val updated = scala.collection.mutable.Set.empty[Int]
    val checkFunction = (v: Int) => {
      // 一定割合以上なら全部足すことにしてみる
      if(beforeUpdated.size / latestDepenencyListMap.size > 0.5) {
        true
      } else {
        beforeUpdated.contains(v)
      }
    } 
    latestDepenencyListMap.toList.map {
      // TODO: 並列化
      // beforeUpdatedは書き込みなしなのでそのまま使える
      // updatedとallMapは書き込みありなのでスレッドセーフなものに置き換える必要がある
      case (id, deps) => {
        if (deps.size > 0) {
          // 初回は自分の子要素を追加して終わり
          if (first) {
            allMap.update(id, deps.toSet)
            updated += id
          } else {
            val old = allMap.get(id)
            val result = deps.map { tid =>
              // oldをあとから足すので、前アップデートされていなかったら足す必要がない。
              if (checkFunction(tid)) {
                allMap.get(tid).getOrElse(Seq.empty)
              } else {
                Seq.empty
              }
            }.flatten.toSet ++ old.getOrElse(Set.empty)
            allMap.update(id, result)
            // oldが存在しなかったらもしくは更新されていたらtrue
            if (old.forall(x => result.size > x.size)) {
              updated += id
            }
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
