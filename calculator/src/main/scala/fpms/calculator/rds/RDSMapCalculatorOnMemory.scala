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
import fpms.RDS

class RDSMapCalculatorOnMemory[F[_]](implicit F: ConcurrentEffect[F], P: Parallel[F], cs: ContextShift[F])
    extends RDSMapCalculator[F]
    with LazyLogging {

  def calc(ldilMap: LDILMap): F[RDSMap] = {
    logger.info("start calculation of RDS")
    val (allMap, updatedZ) = initMap(ldilMap)
    val keyGrouped = allMap.keySet.grouped(allMap.size / 31).zipWithIndex.toList
    var updatedBefore = updatedZ
    val context = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(32))
    logger.info(s"created initalized map - updated size: ${updatedBefore.size}")
    // Loop
    while (updatedBefore.nonEmpty) {
      val list = keyGrouped.map {
        case (v, i) =>
          F.async[Set[Int]](cb => {
            val update = scala.collection.mutable.Set.empty[Int]
            v.foreach(rdsid => {
              ldilMap.get(rdsid).collect {
                case ldil => {
                  val d: Seq[scala.collection.Set[Int]] =
                    ldil.filter(updatedBefore.contains).map(tid => allMap.get(tid).map(_.to)).flatten
                  if (d.nonEmpty) {
                    val set = allMap.get(rdsid).get.to
                    val newSet = (d.flatten).toSet ++ set
                    if (newSet.size > set.size) {
                      update += rdsid
                      allMap.update(rdsid, RDS(newSet))
                    }
                  }
                }
              }
            })
            logger.info(s"end thread $i")
            cb(Right(update.toSet))
          })
      }.toList.parSequence.map(_.flatten.toSet)
      updatedBefore = F.toIO(cs.evalOn(context)(list)).unsafeRunSync()
      logger.info(s"updated size: ${updatedBefore.size}")
    }
    F.pure(allMap.toMap)
  }

  private def initMap(ldilMap: LDILMap): (scala.collection.mutable.Map[Int, RDS], Set[Int]) = {
    val allMap = scala.collection.mutable.Map.empty[Int, RDS]
    val updatedIni = scala.collection.mutable.Set.empty[Int]
    ldilMap.toList.map {
      case (id, set) => {
        if (set.nonEmpty) {
          allMap.update(id, RDS(set.toSet))
          updatedIni += id
        }
      }
    }
    (allMap, updatedIni.toSet)
  }
}
