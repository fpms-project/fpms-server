package fpms.calculator.package_map

import cats.effect.kernel.Async
import cats.implicits.*
import fpms.repository.LibraryPackageRepository
import com.typesafe.scalalogging.LazyLogging
import cats.Parallel

class DBPackageMapGenerator[F[_]: Async](packRepo: LibraryPackageRepository[F])(implicit P: Parallel[F])
    extends PackageMapGenerator[F]
    with LazyLogging {
  val NAME_LIMIT = 100000
  override def getMap = {
    for {
      count <- packRepo.getNameCount()
      _ <- Async[F].pure(logger.info(s"get count of package name (${count})"))
      list <- (0 to scala.math.floor(count / NAME_LIMIT.toDouble).toInt)
        .map(n => scala.math.min(count, n * NAME_LIMIT))
        .map(offset => packRepo.getNameList(NAME_LIMIT, offset))
        .grouped(5)
        .map(v => v.toList.parSequence)
        .toList
        .sequence
        .map(_.flatten.flatten)
      _ <- Async[F].pure(logger.info(s"collected package name ${list.size}"))
      map <- list
        .grouped(1000)
        .map(name => packRepo.findByName(name).map(list => list.groupBy(v => v.name)))
        .grouped(10)
        .zipWithIndex
        .map(v => {
          for {
            z <- v._1.toList.parSequence
            _ <- Async[F].pure(logger.info(s"${v._2 * 10 * 1000}"))
          } yield z
        })
        .toList
        .sequence
        .map(_.flatten.flatten.toMap)
      _ <- Async[F].pure(logger.info("collected package map"))
    } yield map
  }
}
