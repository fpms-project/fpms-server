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
import fpms.calcurator.ldil.LDILContainerOnRedis
import fpms.calcurator.ldil.LDILMapCalculator
import fpms.calcurator.ldil.LDILMapCalculatorOnMemory
import fpms.calcurator.rds.RDSContainer
import fpms.calcurator.rds.RDSContainerOnMemory
import fpms.calcurator.rds.RDSContainerOnRedis
import fpms.calcurator.rds.RDSMapCalculator
import fpms.calcurator.rds.RDSMapCalculatorOnMemory
import fpms.redis.RedisConf
import fpms.repository.LibraryPackageRepository
import fpms.calcurator.ldil.LDILMapCalculatorWithRedis

class LocalDependencyCalculator[F[_]](
    packageRepository: LibraryPackageRepository[F],
    ldilCalcurator: LDILMapCalculator[F],
    ldilContainer: LDILContainer[F],
    rdsMapCalculator: RDSMapCalculator[F],
    rdsContainer: RDSContainer[F]
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
      _ <- F.pure(System.gc())
      x <- rdsMapCalculator.calc(idMap)
      _ <- F.pure(System.gc())
      _ <- ldilContainer.sync(idMap)
      _ <- F.pure(logger.info("ldil sync"))
      _ <- rdsContainer.sync(x)
      _ <- F.pure(logger.info("rds sync"))
      _ <- F.pure(System.gc())
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
      _ <- F.pure(System.gc())
      x <- rdsMapCalculator.calc(idMap)
      _ <- ldilContainer.sync(idMap)
      _ <- rdsContainer.sync(x)
      _ <- F.pure(System.gc())
    } yield ()
  }
}

object LocalDependencyCalculator {
  def create[F[_]: ConcurrentEffect](packageRepository: LibraryPackageRepository[F])(
      implicit P: Parallel[F],
      cs: ContextShift[F],
      timer: Timer[F]
  ): F[LocalDependencyCalculator[F]] =
    for {
      m <- MVar.of[F, Map[Int, List[Int]]](Map.empty)
      m2 <- MVar.of[F, rds.RDSMap](Map.empty)
    } yield new LocalDependencyCalculator(
      packageRepository,
      new LDILMapCalculatorOnMemory[F](),
      new LDILContainerOnMemory[F](m),
      new RDSMapCalculatorOnMemory[F](),
      new RDSContainerOnMemory[F](m2)
    )

  def createForRedisContainer[F[_]](packageRepository: LibraryPackageRepository[F], conf: RedisConf)(
      implicit P: Parallel[F],
      cs: ContextShift[F],
      timer: Timer[F],
      F: ConcurrentEffect[F]
  ): F[LocalDependencyCalculator[F]] =
    for {
      m <- MVar.empty[F, Map[Int, Seq[Int]]]
    } yield new LocalDependencyCalculator(
      packageRepository,
      new LDILMapCalculatorWithRedis[F](packageRepository, conf, m),
      new LDILContainerOnRedis(conf),
      new RDSMapCalculatorOnMemory[F](),
      new RDSContainerOnRedis(conf)
    )
}

final private class MLock[F[_]: ConcurrentEffect](mvar: MVar2[F, Unit]) {
  def acquire: F[Unit] =
    mvar.take

  def release: F[Unit] =
    mvar.put(())
}
