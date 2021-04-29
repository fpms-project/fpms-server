package fpms.calculator

import scala.concurrent.duration._

import cats.effect.ConcurrentEffect
import cats.effect.Timer
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging

import fpms.LibraryPackage
import fpms.repository.AddedPackageIdQueue
import fpms.repository.LDILRepository
import fpms.repository.LibraryPackageRepository
import fpms.repository.RDSRepository

import fpms.calculator.ldil.LDILMapCalculator
import fpms.calculator.rds.RDSMapCalculator

class RedisDependencyCalculator[F[_]](
    packageRepository: LibraryPackageRepository[F],
    ldilCalcurator: LDILMapCalculator[F],
    ldilContainer: LDILRepository[F],
    rdsMapCalculator: RDSMapCalculator[F],
    rdsContainer: RDSRepository[F],
    addedPackageQueue: AddedPackageIdQueue[F]
)(
    implicit F: ConcurrentEffect[F],
    timer: Timer[F]
) extends DependencyCalculator[F]
    with LazyLogging {

  def initialize(): F[Unit] = {
    logger.info("start setup")
    for {
      idMap <- ldilCalcurator.init
      _ <- F.pure(logger.info("complete to calculate ldil for each package"))
      x <- rdsMapCalculator.calc(idMap)
      _ <- ldilContainer.insert(idMap)
      _ <- F.pure(logger.info("ldil is saved"))
      _ <- rdsContainer.insert(x)
      _ <- F.pure(logger.info("rds is saved"))
    } yield ()
  }

  def loop(): F[Unit] = {
    for {
      idlist <- addedPackageQueue.popAll()
      list <- packageRepository.findByIds(idlist.toList)
      _ <- F.pure(logger.info(s"added list : ${list.map(x => s"${x.name}@${x.version.original}").mkString(",")}"))
      _ <- if (list.nonEmpty) update(list) else F.unit
      _ <- timer.sleep(60.seconds)
      _ <- loop()
    } yield ()
  }

  private def update(list: Seq[LibraryPackage]) = {
    for {
      idMap <- ldilCalcurator.update(list)
      x <- rdsMapCalculator.calc(idMap)
      _ <- ldilContainer.insert(idMap)
      _ <- rdsContainer.insert(x)
    } yield ()
  }
}
