package fpms.calcurator

import cats.implicits._
import cats.data.OptionT
import cats.effect.ContextShift
import cats.Parallel
import com.typesafe.scalalogging.LazyLogging

import fpms.calcurator.ldil.LDILContainer
import fpms.calcurator.ldil.LDILContainerOnMemory
import fpms.calcurator.ldil.LDILMapCalculator
import fpms.calcurator.ldil.LDILMapCalculatorOnMemory
import fpms.calcurator.rds.RDSContainer
import fpms.calcurator.rds.RDSContainerOnMemory
import fpms.calcurator.rds.RDSMapCalcurator
import fpms.calcurator.rds.RDSMapCalcuratorOnMemory
import cats.effect.ConcurrentEffect

class LocalDependencyCalculator[F[_]](implicit F: ConcurrentEffect[F], P: Parallel[F], cs: ContextShift[F])
    extends DependencyCalculator[F]
    with LazyLogging {
  private val ldilCalcurator: LDILMapCalculator[F] = new LDILMapCalculatorOnMemory[F]()
  private val ldilContainer: LDILContainer[F] = new LDILContainerOnMemory[F]()
  private val rdsContainer: RDSContainer[F] = new RDSContainerOnMemory[F]()
  private val rdsMapCalculator: RDSMapCalcurator[F] = new RDSMapCalcuratorOnMemory[F]()

  def initialize(): F[Unit] = {
    setup()
  }

  // 一旦
  def getAll = Map.empty[Int, PackageCalcuratedDeps]

  def get(id: Int): F[Option[PackageCalcuratedDeps]] = {
    (for {
      x <- OptionT(ldilContainer.get(id))
      v <- OptionT(rdsContainer.get(id))
    } yield PackageCalcuratedDeps(x, v.toSet)).value
  }

  /**
    * WARNING: same as initilalize
    */
  def load(): F[Unit] = initialize()

  def add(added: AddPackage): F[Unit] = F.pure(())

  private def setup(): F[Unit] = {
    logger.info("start setup")
    for {
      idMap <- ldilCalcurator.init
      _ <- F.pure(System.gc())
      x <- rdsMapCalculator.calc(idMap)
      _ <- ldilContainer.sync(idMap)
      _ <- rdsContainer.sync(x)
      _ <- F.pure(System.gc())
    } yield ()
  }
}
