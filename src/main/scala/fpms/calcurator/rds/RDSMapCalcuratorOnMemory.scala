package fpms.calcurator.rds

import cats.effect.Concurrent
import com.typesafe.scalalogging.LazyLogging
import fpms.calcurator.ldil.LDILMap

class RDSMapCalcuratorOnMemory[F[_]](implicit F: Concurrent[F]) extends RDSMapCalcurator[F] with LazyLogging {

  def calc(ldilMap: LDILMap): F[RDSMap] = {
    val initedMap = initMap(ldilMap)
    val allMap = initedMap._1
    val allMapList = allMap.toList
    var updated = initedMap._2
    // Loop
    while (updated.nonEmpty) {
      logger.info(s"updated size: ${updated.size}")
      val updateInLoop = scala.collection.mutable.Set.empty[Int]
      val checkFunction: (Int => Boolean) =
        if (updated.size / ldilMap.size > 0.5) { (_) => true }
        else updated.contains
      allMapList.foreach {
        case (id, set) => {
          val oldSize = set.size
          ldilMap.get(id).collect {
            case value => {
              value.foreach { tid => if (checkFunction(tid)) set ++= allMap.get(tid).get }
              if (set.size > oldSize) {
                updateInLoop += id
              }
            }
          }
        }
      }
      updated = updateInLoop.toSet
    }
    F.pure(allMap)
  }

  private def initMap(ldilMap: LDILMap): (Map[Int, scala.collection.mutable.Set[Int]], Set[Int]) = {
    val allMap = scala.collection.mutable.Map.empty[Int, scala.collection.mutable.Set[Int]]
    val updatedIni = scala.collection.mutable.TreeSet.empty[Int]
    ldilMap.toList.map {
      case (id, set) => {
        if (set.nonEmpty) {
          allMap.update(id, scala.collection.mutable.Set(set: _*))
          updatedIni += id
        }
      }
    }
    (allMap.toMap, updatedIni.toSet)
  }

}
