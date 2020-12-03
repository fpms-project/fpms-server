package fpms.calcurator.rds

import cats.effect.Concurrent
import com.typesafe.scalalogging.LazyLogging
import fpms.calcurator.ldil.LDILMap

class RDSMapCalcuratorOnMemory[F[_]](implicit F: Concurrent[F]) extends RDSMapCalcurator[F] with LazyLogging {
  type InnerMap = scala.collection.mutable.Map[Int, scala.collection.mutable.Set[Int]]
  def calc(ldilMap: LDILMap): F[RDSMap] = {
    val allMap = scala.collection.mutable.Map.empty[Int, scala.collection.mutable.Set[Int]]
    allMap.clear()
    logger.info(s"start calc all deps")
    // Initialize Map
    val updatedIni = scala.collection.mutable.TreeSet.empty[Int]
    ldilMap.toList.map {
      case (id, set) => {
        if (set.nonEmpty) {
          allMap.update(id, scala.collection.mutable.Set(set: _*))
          updatedIni += id
        }
      }
    }
    var updated = updatedIni.toSet
    // Loop
    while (updated.nonEmpty) {
      logger.info(s"updated size: ${updated.size}")
      val updateInLoop = scala.collection.mutable.Set.empty[Int]
      val checkFunction: (Int => Boolean) =
        if (updated.size / ldilMap.size > 0.5) { (_) => true }
        else updated.contains
        
      allMap.toList.foreach {
        case (id, set) => {
          val oldSize = set.size
          ldilMap.get(id).collect {
            case value => {
              value.foreach { tid => if (checkFunction(tid)) set ++= allMap.get(tid).get }
              if (set.size > oldSize) {
                updateInLoop += id
                allMap.update(id, set)
              }
            }
          }
        }
      }
      updated = updateInLoop.toSet
    }
    F.pure(allMap.map(x => (x._1, x._2.toSet)).toMap)
  }

}
