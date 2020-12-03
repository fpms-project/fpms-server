package fpms.calcurator.ldil

import fpms.LibraryPackage
import fpms.json.JsonLoader
import com.typesafe.scalalogging.LazyLogging
import cats.effect.Async

class LDILMapCalculatorOnMemory[F[_]](implicit F: Async[F]) extends LDILMapCalculator[F] with LazyLogging {
  private val added = scala.collection.mutable.ListBuffer.empty[LibraryPackage]
  def init: F[LDILMap] = {
    updateMap(JsonLoader.createNamePackagesMap())
  }

  def update(adds: Seq[LibraryPackage]): F[LDILMap] = {
    added ++= adds
    // 追加されたパッケージについてpackMapを更新する
    // (packMapに存在していれば（すでに存在するパッケージの新しいバージョンであれば）最後に追加して更新、そうでなければ新しいキーの作成)
    val packMap = scala.collection.mutable.Map.empty[String, Seq[LibraryPackage]] ++ JsonLoader.createNamePackagesMap()
    added.foreach { v => packMap.update(v.name, packMap.get(v.name).getOrElse(Seq.empty) :+ v) }
    updateMap(packMap.toMap)
  }

  private def updateMap(packMap: Map[String, Seq[LibraryPackage]]): F[LDILMap] = {
    val packsGroupedByName: List[List[LibraryPackage]] = packMap.values.toList.map(_.toList)
    val finder = new LatestDependencyFinder(packMap.get)
    val map = scala.collection.mutable.Map.empty[Int, List[Int]]
    logger.info(s"number of names of packages : ${packsGroupedByName.size}")
    packsGroupedByName.zipWithIndex.foreach {
      case (v, i) => {
        if (i % 100000 == 0) logger.info(s"count: ${i}, length: ${map.size}")
        v.foreach { pack =>
          try {
            val ids = finder.findIds(pack)
            map.update(pack.id, ids)
          } catch {
            case _: Throwable => ()
          }
        }
      }
    }
    logger.info(s"complete generating id list map - length: ${map.size}")
    F.pure(map.toMap)
  }
}
