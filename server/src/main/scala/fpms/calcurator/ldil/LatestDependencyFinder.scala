package fpms.calcurator.ldil

import fpms.LibraryPackage

class LatestDependencyFinder(findFunc: (String) => Option[Seq[LibraryPackage]]) {
  import VersionFinder._

  private val depCache = scala.collection.mutable.Map.empty[(String, String), Int]

  // パッケージの依存関係指定から、現在のIDの集合を取得する
  def findIds(p: LibraryPackage): List[Int] = {
    try {
      p.deps.map { con =>
        depCache.get(con) match {
          case Some(value) => value
          case None        => findIdOfLatestByCondition(con._1, con._2)
        }
      }.toList
    } catch {
      case _: NoSuchElementException => throw new Error("can't get calcurate deps")
      case a: Throwable              => throw a
    }
  }

  // nameとversionからその条件に合う最適な答えを出す
  private def findIdOfLatestByCondition(name: String, verisonCond: String): Int = {
    (for {
      packs <- findFunc(name)
      x <- packs.latestInFits(verisonCond)
    } yield {
      // キャッシュに保存
      depCache.update((name, verisonCond), x.id)
      x.id
    }).get
  }
}
