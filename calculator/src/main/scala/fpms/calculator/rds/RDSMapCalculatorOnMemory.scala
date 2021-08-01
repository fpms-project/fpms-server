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
    val keyGrouped = allMap.keySet.grouped(allMap.size / 31).toList
    var updatedBefore = updatedZ
    val context = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(32))
    logger.info(s"created initalized map - updated size: ${updatedBefore.size}")
    val updateDeps = (rdsid: Int) => {
      val updates = ldilMap.get(rdsid).get.filter(updatedBefore.contains)
      if (updates.nonEmpty) {
        val beforeRds = allMap.get(rdsid).get
        val depsList = updates.map(tid => allMap.get(tid)).flatten.flatten
        val newList = (depsList ++ beforeRds).distinct
        if (newList.size > beforeRds.size) {
          allMap.update(rdsid, newList.toArray)
          Some(rdsid)
        } else {
          None
        }
      } else {
        None
      }
    }
    // Loop
    while (updatedBefore.nonEmpty) {
      val list = keyGrouped.map { v =>
        F.async[Set[Int]](cb => {
          val update = scala.collection.mutable.Set.empty[Int]
          v.foreach(rdsid => updateDeps(rdsid).collect(v => update += v))
          cb(Right(update.toSet))
        })
      }.toList.parSequence.map(_.flatten.toSet)
      updatedBefore = F.toIO(cs.evalOn(context)(list)).unsafeRunSync()
      logger.info(s"updated size: ${updatedBefore.size}")
    }
    logger.info("complete to calculate rds for each package")
    F.pure(allMap.toMap)
  }

  private def initMap(ldilMap: LDILMap): (scala.collection.mutable.Map[Int, Array[Int]], Set[Int]) = {
    val allMap = scala.collection.mutable.LinkedHashMap.empty[Int, Array[Int]]
    val updatedIni = scala.collection.mutable.Set.empty[Int]
    ldilMap.toList.map {
      case (id, set) => {
        if (set.nonEmpty) {
          allMap.update(id, set.toArray)
          updatedIni += id
        }
      }
    }
    (allMap, updatedIni.toSet)
  }
}
