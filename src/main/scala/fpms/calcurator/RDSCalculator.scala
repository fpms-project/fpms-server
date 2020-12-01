package fpms.calcurator

import com.typesafe.scalalogging.LazyLogging

import LDILMapGenerator.LDILMap
import RDSCalculator._
import fpms.LibraryPackage

class RDSContainer {

  def saveRDS() = ???
  def saveLatestDepIdListMap() = ???

  def add(packs: Seq[LibraryPackage]): LDILMap = ??? 

  def get(id: Int):  Option[PackageCalcuratedDeps] = ???
}

/**　
  * RDS(Recursive Dependency Set) Calcurator
  */
class RDSCalculator extends LazyLogging {
  private var status: AllDepsCalcuratorStatus = NoData
  private lazy val allMap = scala.collection.mutable.Map.empty[Int, scala.collection.mutable.Set[Int]]
  private var latestDepenencyListMap: LDILMap = Map.empty[Int, List[Int]]

  def getStatus: AllDepsCalcuratorStatus = status

  def calcAllDep(latestDepenencyListMap: LDILMap): Unit = {
    this.latestDepenencyListMap = latestDepenencyListMap
    this.status = Computing
    allMap.clear()
    logger.info(s"start calc all deps")
    var updated = initalizeMap()
    while (updated.nonEmpty) {
      logger.info(s"updated size: ${updated.size}")
      updated = updateMapInfo(updated)
    }
    this.status = Computed
  }

  def get(id: Int): Option[PackageCalcuratedDeps] = {
    status match {
      case Computed =>
        Some(
          PackageCalcuratedDeps(
            latestDepenencyListMap.get(id).getOrElse(Seq.empty),
            allMap.get(id).map(_.toSet).getOrElse(Set.empty)
          )
        )
      case _ => None
    }
  }

  // TODO: 多分メモリ不足で死ぬ
  def getAll = {
    latestDepenencyListMap
      .map(v => (v._1, PackageCalcuratedDeps(v._2, allMap.get(v._1).map(_.toSet).getOrElse(Set.empty))))
      .toMap[Int, PackageCalcuratedDeps]
  }

  // Mapに最初の
  private def initalizeMap(): Set[Int] = {
    val updated = scala.collection.mutable.TreeSet.empty[Int]
    latestDepenencyListMap.toList.map {
      case (id, set) => {
        if (set.nonEmpty) {
          allMap.update(id, scala.collection.mutable.Set(set: _*))
          updated += id
        }
      }
    }
    updated.toSet
  }

  private def updateMapInfo(beforeUpdated: Set[Int]): Set[Int] = {
    val updated = scala.collection.mutable.Set.empty[Int]
    val checkFunction: (Int => Boolean) =
      // 一定割合以上なら全部足すことにしてみる
      if (beforeUpdated.size / latestDepenencyListMap.size > 0.5) { (_) => true }
      else {
        beforeUpdated.contains
      }
    allMap.toList.foreach {
      case (id, set) => {
        val oldSize = set.size
        latestDepenencyListMap.get(id).collect {
          case value => {
            value.foreach { tid => if (checkFunction(tid)) set ++= allMap.get(tid).get }
            if (set.size > oldSize) {
              updated += id
              allMap.update(id, set)
            }
          }
        }
      }
    }
    updated.toSet
  }
}

object RDSCalculator {
  sealed abstract class AllDepsCalcuratorStatus
  case object NoData extends AllDepsCalcuratorStatus
  case object Computing extends AllDepsCalcuratorStatus
  case object Computed extends AllDepsCalcuratorStatus
}
