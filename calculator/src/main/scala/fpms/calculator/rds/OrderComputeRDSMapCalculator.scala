package fpms.calculator.rds

import com.typesafe.scalalogging.LazyLogging

import fpms.LDIL
import fpms.RDS
import cats.effect.kernel.Async

class OrderComputeRDSMapCalculator[F[_]: Async]()
    extends RDSMapCalculator[F] with LazyLogging {
  def calc(ldilMap: LDIL.LDILMap): F[RDS.RDSMap] = {
    val allMap = scala.collection.mutable.LinkedHashMap.empty[Int, Array[Int]]
    ldilMap.zipWithIndex.foreach(v => {
      if(v._2 % 1000000 == 0) logger.info(s"compute ${v._2} / ${ldilMap.size}")
      val rds = calcRDS(v._1._1, ldilMap, allMap)
      allMap.addOne((v._1._1, rds))
    })
    Async[F].pure(allMap.toMap)
  }

  def calcRDS(id: Int, ldilMap: LDIL.LDILMap, allMap: scala.collection.mutable.LinkedHashMap[Int, Array[Int]]) = {
    val set = scala.collection.mutable.HashSet(ldilMap.get(id).getOrElse(Seq.empty)*)
    val request = scala.collection.mutable.HashSet(ldilMap.get(id).getOrElse(Seq.empty)*)
    val requested = scala.collection.mutable.HashSet.empty[Int]
    while (request.size > 0) {
      request.toSeq.foreach((id: Int) => {
        requested.add(id)
        if (allMap.get(id).isDefined) {
          set.addAll(allMap.get(id).get)
        } else if(ldilMap.get(id).isDefined) {
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
