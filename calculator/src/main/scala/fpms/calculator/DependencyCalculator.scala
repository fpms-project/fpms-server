package fpms.calculator

import scala.concurrent.duration.*

import cats.implicits.*
import cats.effect.implicits.*
import cats.effect.Temporal
import com.typesafe.scalalogging.LazyLogging

import fpms.LibraryPackage
import fpms.repository.AddedPackageIdQueue
import fpms.repository.LDILRepository
import fpms.repository.LibraryPackageRepository
import fpms.repository.RDSRepository

import fpms.calculator.ldil.LDILMapCalculator
import fpms.calculator.rds.RDSMapCalculator
import cats.effect.kernel.Async

class DependencyCalculator[F[_]: Async](
    packageRepository: LibraryPackageRepository[F],
    ldilCalcurator: LDILMapCalculator[F],
    ldilContainer: LDILRepository[F],
    rdsMapCalculator: RDSMapCalculator[F],
    rdsContainer: RDSRepository[F],
    addedPackageQueue: AddedPackageIdQueue[F]
) extends LazyLogging {

  def initialize(): F[Unit] = {
    logger.info("start setup")
    for {
      idMap <- ldilCalcurator.init
      _ <- Async[F].pure(logger.info("complete to calculate ldil for each package"))
      x <- rdsMapCalculator.calc(idMap)
      _ <- ldilContainer.insert(idMap)
      _ <- Async[F].pure(logger.info("ldil is saved"))
      _ <- rdsContainer.insert(x)
      _ <- Async[F].pure(logger.info("rds is saved"))
    } yield ()
  }

  def loop(): F[Unit] = {
    for {
      idlist <- addedPackageQueue.popAll()
      list <- packageRepository.findByIds(idlist.toList)
      _ <- if (list.isEmpty) Async[F].pure(())
      else Async[F].pure(logger.info(s"added list : ${list.map(x => s"${x.name}@${x.version.original}").mkString(",")}"))
      _ <- if (list.nonEmpty) update(list) else Async[F].unit
      _ <- Temporal[F].sleep(60.seconds)
      _ <- loop()
    } yield ()
  }

  private def update(list: Seq[LibraryPackage]) = {
    val listtext = list.map(x => s"${x.name}@${x.version.original}").mkString(",")
    for {
      _ <- Async[F].pure(logger.info(s"start_update_by_add_package : ${listtext}"))
      idMap <- ldilCalcurator.update(list)
      x <- rdsMapCalculator.calc(idMap)
      _ <- ldilContainer.insert(idMap)
      _ <- rdsContainer.insert(x)
      _ <- Async[F].pure(
        logger.info(s"end_update_by_add_package : ${listtext}")
      )
    } yield ()
  }
}
