package fpms.calculator.rds

import java.util.concurrent.Executors

import scala.concurrent.ExecutionContext

import cats.Parallel
import cats.effect.ConcurrentEffect
import cats.effect.ContextShift
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging

import fpms.LDIL.LDILMap
import fpms.RDS.RDSMap

class RDSMapCalculatorOnMemory[F[_]](implicit F: ConcurrentEffect[F], P: Parallel[F], cs: ContextShift[F])
    extends RDSMapCalculator[F]
    with LazyLogging {

  def calc(ldilMap: LDILMap): F[RDSMap] = {
    logger.info("start calculation of RDS")
    val (allMap, updatedZ) = initMap(ldilMap)
    val allMapList = allMap.toList.grouped(allMap.size / 31).zipWithIndex.toList
    var updated = updatedZ
    val context = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(32))
    logger.info(s"created initalized map - updated size: ${updated.size}")
    // Loop
    while (updated.nonEmpty) {
      val list = allMapList.map {
        case (v, i) =>
          F.async[Set[Int]](cb => {
            val update = scala.collection.mutable.Set.empty[Int]
            v.foreach {
              case (id, set) => {
                val oldSize = set.size;
                ldilMap.get(id).collect { value =>
                  value.foreach { tid => if (updated.contains(tid)) set ++= allMap.get(tid).getOrElse(Set.empty) }
                }
                if (set.size > oldSize) update += id
              }
            }
            logger.info(s"end thread $i")
            cb(Right(update.toSet))
          })
      }.toList.parSequence.map(_.flatten.toSet)
      updated = F.toIO(cs.evalOn(context)(list)).unsafeRunSync()
      logger.info(s"updated size: ${updated.size}")
    }
    F.pure(allMap)
  }

  private def initMap(ldilMap: LDILMap): (Map[Int, scala.collection.mutable.Set[Int]], Set[Int]) = {
    val allMap = scala.collection.mutable.Map.empty[Int, scala.collection.mutable.Set[Int]]
    val updatedIni = scala.collection.mutable.Set.empty[Int]
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
