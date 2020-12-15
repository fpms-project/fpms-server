package fpms.calcurator.rds

import java.util.concurrent.Executors

import scala.concurrent.ExecutionContext

import cats.Parallel
import cats.effect.ConcurrentEffect
import cats.effect.ContextShift
import cats.effect.concurrent.MVar2
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging

import fpms.calcurator.ldil.LDILMap
import cats.effect.concurrent.MVar

class RDSMapCalculatorOnMemory[F[_]](implicit F: ConcurrentEffect[F], P: Parallel[F], cs: ContextShift[F])
    extends RDSMapCalculator[F]
    with LazyLogging {

  def calc(ldilMap: LDILMap): F[RDSMap[F]] = {
    logger.info("start calculation of RDS")
    val (allMap, updatedZ) = initMap(ldilMap)
    val allMapList = allMap.toList.grouped(allMap.size / 16).zipWithIndex.toList
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
              case (id, setM) => {
                F.toIO(for {
                    set <- setM.read
                    newS <- F.pure(scala.collection.mutable.Set.from(set))
                    _ <- F.pure(ldilMap.get(id).collect { ldil =>
                      ldil.foreach { tid =>
                        if (updated.contains(tid)) {
                          F.toIO(allMap.get(tid).fold(F.unit)(_.read.map(y => { newS ++= y; () }))).unsafeRunSync()
                        } else F.unit
                      }
                    })
                    _ <- if (newS.size > set.size) setM.swap(newS.toSet).map(_ => update += id) else F.pure(())
                  } yield ())
                  .unsafeRunSync()
              }
            }
            logger.info(s"end thread $i")
            cb(Right(update.toSet))
          })
      }.toList.parSequence.map(_.flatten.toSet)
      updated = if (true) { F.toIO(list).unsafeRunSync() }
      else F.toIO(cs.evalOn(context)(list)).unsafeRunSync()
      logger.info(s"updated size: ${updated.size}")
    }
    F.pure(allMap)
  }

  private def initMap(ldilMap: LDILMap): (Map[Int, MVar2[F, Set[Int]]], Set[Int]) = {
    val allMap = scala.collection.mutable.Map.empty[Int, MVar2[F, Set[Int]]]
    val updatedIni = scala.collection.mutable.Set.empty[Int]
    ldilMap.toList.map {
      case (id, set) => {
        if (set.nonEmpty) {
          allMap.update(id, F.toIO(MVar.of(Set.from(set))).unsafeRunSync())
          updatedIni += id
        }
      }
    }
    (allMap.toMap, updatedIni.toSet)
  }
}
