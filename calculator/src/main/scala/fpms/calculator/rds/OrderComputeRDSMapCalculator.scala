package fpms.calculator.rds

import java.util.concurrent.Executors

import scala.concurrent.ExecutionContext

import cats.Parallel
import cats.effect.ConcurrentEffect
import cats.effect.ContextShift
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging

import fpms.LDIL
import fpms.RDS

// 並列化するとどこかでデッドロックになって動かないっぽい。
// 本質的な話でもないのでしばらく放置しておく
class OrderComputeRDSMapCalculator[F[_]](implicit F: ConcurrentEffect[F], P: Parallel[F], cs: ContextShift[F])
    extends RDSMapCalculator[F]
    with LazyLogging {

  lazy val THREAD_NUM = Runtime.getRuntime().availableProcessors() / 2
  lazy val context = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(THREAD_NUM))

  def calc(ldilMap: LDIL.LDILMap): F[RDS.RDSMap] = {
    logger.info("start compute rds")
    val allMap = scala.collection.mutable.LinkedHashMap.empty[Int, Array[Int]]
    val grouped = ldilMap.keys.grouped(ldilMap.size / Math.max(THREAD_NUM - 1, 1)).zipWithIndex.toList
    val func = grouped
      .map(group => {
        logger.info(s"start computes in thread ${group._2}")
        F.async[Unit](cb => {
          var count = 0
          group._1.foreach(v => {
            if (count % 100 == 0) logger.info(s"compute ${count} th on thread ${group._2}")
            val rds = calcRDS(v, ldilMap, allMap)
            allMap.addOne((v, rds))
            count += 1
          })
          logger.info(s"end thread ${group._2}")
          cb(Right(()))
        })
      })
      .toList
      .parSequence_
    F.toIO(cs.evalOn(context)(func)).unsafeRunSync()
    F.pure(allMap.toMap)
  }

  def calcRDS(id: Int, ldilMap: LDIL.LDILMap, allMap: scala.collection.mutable.LinkedHashMap[Int, Array[Int]]) = {
    val set = scala.collection.mutable.HashSet(ldilMap.get(id).getOrElse(Seq.empty): _*)
    val request = scala.collection.mutable.HashSet(ldilMap.get(id).getOrElse(Seq.empty): _*)
    val requested = scala.collection.mutable.HashSet.empty[Int]
    while (request.size > 0) {
      val thisList = request.toSeq
      thisList.foreach((id: Int) => {
        requested.add(id)
        if (allMap.get(id).isDefined) {
          set.addAll(allMap.get(id).get)
        } else if (ldilMap.get(id).isDefined) {
          val ldil = ldilMap.get(id).get
          set.addAll(ldil)
          request.addAll(ldil.filterNot(requested.contains))
        }
        request.remove(id)
      })
    }
    set.toArray
  }
}
