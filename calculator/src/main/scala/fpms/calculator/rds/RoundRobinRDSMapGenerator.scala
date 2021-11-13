package fpms.calculator.rds

import java.util.concurrent.Executors

import cats.Parallel
import cats.effect.kernel.Async
import cats.implicits.*
import com.typesafe.scalalogging.LazyLogging
import doobie.util.update

import fpms.LDIL.LDILMap
import fpms.RDS.RDSMap

/** 間接依存をRound Robinアルゴリズムで計算するCalculator
  */
class RoundRobinRDSMapGenerator[F[_]: Async](implicit P: Parallel[F]) extends RDSMapGenerator[F] with LazyLogging {

  lazy val THREAD_NUM = Runtime.getRuntime().availableProcessors()

  override def generate(ldilMap: LDILMap): F[RDSMap] = {
    logger.info("start calculation of RDS")
    val (allMap, updatedBefore) = initMap(ldilMap)
    val keyGrouped = allMap.keySet.grouped(allMap.size / (THREAD_NUM - 1)).toList
    logger.info(s"created initalized map - updated size: ${updatedBefore.size}")
    loop(ldilMap, allMap, keyGrouped, updatedBefore)
  }

  def loop(
      ldilMap: LDILMap,
      allMap: scala.collection.mutable.Map[Int, Array[Int]],
      keyGrouped: List[scala.collection.Set[Int]],
      updatedBefore: Set[Int]
  ): F[RDSMap] = {
    for {
      updatedBefore <- keyGrouped.map { idArray =>
        Async[F].async_[Set[Int]](cb => {
          // それぞれのidについてupdateDepsを実行、更新されたものはSome[Int]が帰ってくるのでflattenして集合にする
          cb(Right(idArray.map(rdsid => updateDeps(rdsid, ldilMap, allMap, updatedBefore)).flatten.toSet))
        })
      // 並列で実行してSetにする
      }.toList.parSequence.map(_.flatten.toSet)
      z <-
        if (updatedBefore.isEmpty) {
          // 計算が終了したらそのまま返す
          logger.info("complete to calculate rds for each package")
          Async[F].pure(allMap.toMap)
        } else {
          // そうでなければ再帰呼び出し
          logger.info(s"rds updated package size: ${updatedBefore.size}")
          loop(ldilMap, allMap, keyGrouped, updatedBefore)
        }
    } yield z
  }

  private def updateDeps(
      rdsid: Int,
      ldilMap: LDILMap,
      allMap: scala.collection.mutable.Map[Int, Array[Int]],
      updatedBefore: Set[Int]
  ): Option[Int] = {
    val updatedDepIds = ldilMap.get(rdsid).get.filter(updatedBefore.contains)
    // 更新されたものが何もなければNone
    if (updatedDepIds.isEmpty) return None
    val beforeRds = allMap.get(rdsid).get
    val depsList = updatedDepIds.map(tid => allMap.get(tid)).flatten.flatten
    val newList = (depsList ++ beforeRds).distinct
    if (newList.size > beforeRds.size) {
      allMap.update(rdsid, newList.toArray)
      Some(rdsid)
    } else {
      None
    }
  }

  private def initMap(ldilMap: LDILMap): (scala.collection.mutable.Map[Int, Array[Int]], Set[Int]) = {
    val all =
      scala.collection.mutable.LinkedHashMap.from(ldilMap.toList.filter(_._2.nonEmpty).map(v => (v._1, v._2.toArray)))
    (all, all.keySet.toSet)
  }
}
