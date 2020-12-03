package fpms.calcurator

import cats.effect.Concurrent
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging

import fpms.calcurator.ldil.LDILContainer
import fpms.calcurator.ldil.LDILContainerOnMemory
import fpms.calcurator.ldil.LDILMapCalculator
import fpms.calcurator.ldil.LDILMapCalculatorOnMemory

class LocalDependencyCalculator[F[_]](implicit F: Concurrent[F]) extends DependencyCalculator[F] with LazyLogging {
  private val allDepsCalcurator: RDSCalculator = new RDSCalculator()
  private val ldilCalcurator: LDILMapCalculator[F] = new LDILMapCalculatorOnMemory[F]()
  private val ldilContainer: LDILContainer[F] = new LDILContainerOnMemory[F]()

  def initialize(): F[Unit] = {
    setup()
  }

  def getAll = allDepsCalcurator.getAll

  def get(id: Int): F[Option[PackageCalcuratedDeps]] = F.pure(allDepsCalcurator.get(id))

  /**
    * WARNING: same as initilalize
    */
  def load(): F[Unit] = initialize()

  def add(added: AddPackage): F[Unit] = F.pure(())

  private def setup(): F[Unit] = {
    logger.info("start setup")
    for {
      _ <- ldilCalcurator.init
      idMap <- ldilCalcurator.map
      _ <- F.pure(System.gc())
      _ <- F.pure(allDepsCalcurator.calcAllDep(idMap))
      _ <- ldilContainer.sync(idMap)
    } yield ()
  }
}
