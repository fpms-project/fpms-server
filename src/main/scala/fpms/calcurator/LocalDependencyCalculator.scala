package fpms.calcurator

import scala.concurrent.duration._

import cats.Parallel
import cats.effect.ConcurrentEffect
import cats.effect.ContextShift
import cats.effect.Timer
import cats.effect.concurrent.MVar
import cats.effect.concurrent.MVar2
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging

import fpms.LibraryPackage
import fpms.calcurator.ldil.LDILContainer
import fpms.calcurator.ldil.LDILContainerOnMemory
import fpms.calcurator.ldil.LDILMapCalculator
import fpms.calcurator.ldil.LDILMapCalculatorOnMemory
import fpms.calcurator.rds.RDSContainer
import fpms.calcurator.rds.RDSContainerOnMemory
import fpms.calcurator.rds.RDSMapCalcurator
import fpms.calcurator.rds.RDSMapCalcuratorOnMemory

class LocalDependencyCalculator[F[_]](
    implicit F: ConcurrentEffect[F],
    P: Parallel[F],
    cs: ContextShift[F],
    timer: Timer[F]
) extends DependencyCalculator[F]
    with LazyLogging {
  private val ldilCalcurator: LDILMapCalculator[F] = new LDILMapCalculatorOnMemory[F]()
  private val ldilContainer: LDILContainer[F] = new LDILContainerOnMemory[F]()
  private val rdsContainer: RDSContainer[F] = new RDSContainerOnMemory[F]()
  private val rdsMapCalculator: RDSMapCalcurator[F] = new RDSMapCalcuratorOnMemory[F]()
  private val mlock = F.toIO(MVar.of[F, Unit](()).map(new MLock(_))).unsafeRunSync()
  private val addQueue = F.toIO(MVar.of[F, Seq[LibraryPackage]](Seq.empty)).unsafeRunSync()
  private var currentId = 0

  def initialize(): F[Unit] = {
    setup().map(_ => F.toIO(loop()).unsafeRunAsyncAndForget())
  }

  // 一旦
  def getAll = Map.empty[Int, PackageCalcuratedDeps]

  def get(id: Int): F[Option[PackageCalcuratedDeps]] = {
    for {
      x <- ldilContainer.get(id)
      v <- rdsContainer.get(id)
    } yield Some(PackageCalcuratedDeps(x.getOrElse(Seq.empty[Int]), v.map(_.toSet).getOrElse(Set.empty)))
  }

  /**
    * WARNING: same as initilalize
    */
  def load(): F[Unit] = initialize()

  def add(added: AddPackage): F[Unit] = {
    for {
      q <- addQueue.take
      x <- F.pure(LibraryPackage(added.name, added.version, Some(added.deps), currentId))
      _ <- F.pure(currentId += 1)
      _ <- addQueue.put(q :+ x)
    } yield ()
  }

  private def loop(): F[Unit] = {
    for {
      _ <- timer.sleep(60.seconds)
      _ <- mlock.acquire
      list <- addQueue.take
      _ <- F.pure(logger.info(s"added list : ${if (list.isEmpty) "empty"
      else list.map(x => s"${x.name}@${x.version.original}").mkString(",")}"))
      _ <- addQueue.put(Seq.empty)
      _ <- if (list.nonEmpty) update(list) else F.unit
      _ <- mlock.release
      _ <- loop()
    } yield ()
  }

  private def update(list: Seq[LibraryPackage]) = {
    for {
      idMap <- ldilCalcurator.update(list)
      _ <- F.pure(System.gc())
      x <- rdsMapCalculator.calc(idMap)
      _ <- ldilContainer.sync(idMap)
      _ <- rdsContainer.sync(x)
      _ <- F.pure(System.gc())
    } yield ()
  }

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

final class MLock[F[_]: ConcurrentEffect](mvar: MVar2[F, Unit]) {
  def acquire: F[Unit] =
    mvar.take

  def release: F[Unit] =
    mvar.put(())
}
