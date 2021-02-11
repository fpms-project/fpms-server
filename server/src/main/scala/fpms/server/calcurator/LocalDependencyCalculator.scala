package fpms.server.calcurator

import scala.concurrent.duration._

import cats.effect.ConcurrentEffect
import cats.effect.Timer
import cats.effect.concurrent.MVar
import cats.effect.concurrent.MVar2
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging

import fpms.LibraryPackage
import fpms.repository.LibraryPackageRepository
import fpms.repository.RDSRepository
import fpms.repository.LDILRepository
import fpms.server.calcurator.ldil.LDILMapCalculator
import fpms.server.calcurator.rds.RDSMapCalculator

class LocalDependencyCalculator[F[_]](
    packageRepository: LibraryPackageRepository[F],
    ldilCalcurator: LDILMapCalculator[F],
    ldilContainer: LDILRepository[F],
    rdsMapCalculator: RDSMapCalculator[F],
    rdsContainer: RDSRepository[F]
)(
    implicit F: ConcurrentEffect[F],
    timer: Timer[F]
) extends DependencyCalculator[F]
    with LazyLogging {
  private val mlock = F.toIO(MVar.of[F, Unit](()).map(new MLock(_))).unsafeRunSync()
  private val addQueue = F.toIO(MVar.of[F, Seq[LibraryPackage]](Seq.empty)).unsafeRunSync()
  F.toIO(loop()).unsafeRunAsyncAndForget()

  def initialize(): F[Unit] = {
    logger.info("start setup")
    for {
      _ <- mlock.acquire
      idMap <- ldilCalcurator.init
      x <- rdsMapCalculator.calc(idMap)
      _ <- ldilContainer.insert(idMap)
      _ <- F.pure(logger.info("ldil sync"))
      _ <- rdsContainer.insert(x)
      _ <- F.pure(logger.info("rds sync"))
      _ <- mlock.release
    } yield ()
  }

  // 一旦
  def getAll = Map.empty[Int, PackageCalcuratedDeps]

  def get(id: Int): F[Option[PackageCalcuratedDeps]] = {
    for {
      x <- ldilContainer.get(id)
      v <- rdsContainer.get(id)
    } yield Some(PackageCalcuratedDeps(x.getOrElse(Seq.empty[Int]), v.map(_.toSet).getOrElse(Set.empty)))
  }

  def add(added: AddPackage): F[Unit] = {
    for {
      defined <- packageRepository.findOne(added.name, added.version).map(_.isDefined)
      _ <- if (defined) F.raiseError(new Throwable("added package is already exists")) else F.pure(())
      q <- addQueue.take
      id <- packageRepository.getMaxId()
      x <- F.pure(LibraryPackage(added.name, added.version, Some(added.deps), id + 1))
      _ <- packageRepository.insert(x)
      _ <- addQueue.put(q :+ x)
    } yield ()
  }

  private def loop(): F[Unit] = {
    for {
      _ <- timer.sleep(60.seconds)
      _ <- mlock.acquire
      list <- addQueue.take
      _ <- F.pure(logger.info(s"added list : ${list.map(x => s"${x.name}@${x.version.original}").mkString(",")}"))
      _ <- addQueue.put(Seq.empty)
      _ <- if (list.nonEmpty) update(list) else F.unit
      _ <- mlock.release
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


final private class MLock[F[_]: ConcurrentEffect](mvar: MVar2[F, Unit]) {
  def acquire: F[Unit] =
    mvar.take

  def release: F[Unit] =
    mvar.put(())
}
