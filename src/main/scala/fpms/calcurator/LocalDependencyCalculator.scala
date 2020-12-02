package fpms.calcurator

import com.typesafe.scalalogging.LazyLogging
import fpms.calcurator.ldil.JsonMemoryLDILMapCalcurator
import cats.effect.Async
import cats.implicits._

class LocalDependencyCalculator[F[_]](implicit F: Async[F]) extends DependencyCalculator[F] with LazyLogging {
  private val allDepsCalcurator: RDSCalculator = new RDSCalculator()

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
    val ldilCalcurator = new JsonMemoryLDILMapCalcurator[F]()
    for {
      _ <- ldilCalcurator.init
      idMap <- ldilCalcurator.map
      _ <- F.pure(System.gc())
      _ <- F.pure(allDepsCalcurator.calcAllDep(idMap))
    } yield ()
  }
}
