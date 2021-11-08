package fpms.calculator.ldil

import fpms.LibraryPackage

class LatestDependencyFinder(findFunc: (String) => Option[Seq[LibraryPackage]]) {
  import VersionFinder.*

  private val depCache = scala.collection.mutable.Map.empty[(String, String), Int]

  // パッケージの依存関係指定から、現在のIDの集合を取得する
  def findIds(p: LibraryPackage): List[Int] = {
    val list = p.deps.map { con =>
      depCache.get(con) match {
        case Some(value) => Right(value)
        case None        => findIdOfLatestByCondition(con._1, con._2)
      }
    }.toList
    if (list.forall(_.isRight)) {
      list.collect { case Right(value) => value }.toList
    } else {
      throw new InterruptedException(
        s"failed calculate deps of ${p.name}@${p.version.original}: (${list.collect { case Left(value) => value }.mkString(",")})"
      )
    }
  }

  // nameとversionからその条件に合う最適な答えを出す
  private def findIdOfLatestByCondition(name: String, verisonCond: String): Either[String, Int] = {
    val pack = for {
      packs <- findFunc(name).toRight(s"not found $name package")
      x <- packs
        .latestInFits(verisonCond)
        .left
        .map {
          _ match {
            case NotFound        => s"not found package satisfying $verisonCond in $name"
            case ParseRangeError => s"failed to parse range: $verisonCond of $name"
          }
        }
        .map { _.id }
    } yield x
    pack match {
      case Right(value) => {
        depCache.update((name, verisonCond), value)
      }
      case _ => ()
    }
    // キャッシュに保存
    pack

  }
}
