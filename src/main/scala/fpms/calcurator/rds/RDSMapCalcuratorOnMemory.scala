package fpms.calcurator.rds

import scala.concurrent.Await
import scala.concurrent.ExecutionContext

import cats.Parallel
import cats.effect.ConcurrentEffect
import cats.effect.ContextShift
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import java.util.concurrent.Executors

import fpms.calcurator.ldil.LDILMap

class RDSMapCalcuratorOnMemory[F[_]](implicit F: ConcurrentEffect[F], P: Parallel[F], cs: ContextShift[F])
    extends RDSMapCalcurator[F]
    with LazyLogging {

  def calc(ldilMap: LDILMap): F[RDSMap] = {
    val initedMap = initMap(ldilMap)
    val allMap = initedMap._1
    val allMapList = allMap.toList.grouped(allMap.size / 15).zipWithIndex.toList
    var updated = initedMap._2
    val context = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(15))
    logger.info(s"created initalized map - updated size: ${updated.size}")
    // Loop
    while (updated.nonEmpty) {
      val checkFunction: (Int => Boolean) =
        if (updated.size / ldilMap.size > 0.5) { (_) => true }
        else updated.contains
      val list: F[List[Int]] = allMapList.map {
        case (v, i) => {
          val f = () => {
            val update = scala.collection.mutable.Set.empty[Int]
            v.foreach {
              case (id, set) => {
                val oldSize = set.size;
                ldilMap.get(id).collect { value =>
                  value.foreach { tid => if (checkFunction(tid)) set ++= allMap.get(tid).get }
                }
                if (set.size > oldSize) update += id
              }
            }
            logger.info(s"  end thread: $i")
            update.toSet
          }
          F.async[Set[Int]](cb => cb(Right(f())))
        }
      }.toList.parSequence.map(_.flatten)
      updated = F.toIO(cs.evalOn(context)(list)).unsafeRunSync().toSet
      logger.info(s"updated size: ${updated.size}")
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
