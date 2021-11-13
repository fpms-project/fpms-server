package fpms.calculator.package_map

import cats.effect.kernel.Async
import cats.implicits.*
import fpms.repository.LibraryPackageRepository
import com.typesafe.scalalogging.LazyLogging
import cats.Parallel

/** DB(PostgreSQL)からPackageMapを作る
  *
  * @constructor
  *   LibraryPackageRepositoryからパッケージを作る
  */
class DBPackageMapGenerator[F[_]: Async](packRepo: LibraryPackageRepository[F])(implicit P: Parallel[F])
    extends PackageMapGenerator[F]
    with LazyLogging {
  val NAME_LIMIT = 100000
  override def getMap = {
    for {
      count <- packRepo.getNameCount()
      _ <- Async[F].pure(logger.info(s"get count of package name (${count})"))
      // 0から名前の配列の個数を100000で割った数を計算
      list <- (0 to scala.math.floor(count / NAME_LIMIT.toDouble).toInt)
        .map(n => scala.math.min(count, n * NAME_LIMIT)) // offsetを計算 (0, 100000, 200000, ..., count(パッケージ数の最大数))
        .map(offset => packRepo.getNameList(NAME_LIMIT, offset)) // nameを取得
        .grouped(5) // を5並列で行う
        .map(v => v.toList.parSequence)
        .toList
        .sequence
        .map(_.flatten.flatten)
      _ <- Async[F].pure(logger.info(s"collected package name ${list.size}"))
      map <- list
        .grouped(1000) // 1000個ずつ名前をgrouping
        .map(name => packRepo.findByName(name).map(list => list.groupBy(v => v.name))) // 1000個分の名前のパッケージを取得
        .grouped(10) // を10並列で行う
        .map(_.toList.parSequence)
        .toList
        .sequence // のを逐次処理で行う
        .map(_.flatten.flatten.toMap)
      _ <- Async[F].pure(logger.info("collected package map"))
    } yield map
  }
}
