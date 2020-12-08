package fpms.calcurator.rds

import cats.effect.concurrent.MVar
import cats.implicits._
import cats.effect.ConcurrentEffect

class RDSContainerOnMemory[F[_]](implicit F: ConcurrentEffect[F]) extends RDSContainer[F] {
  private val mvar = F.toIO(MVar.of[F, RDSMap[F]](Map.empty[Int, MVar[F, Set[Int]]])).unsafeRunSync()

  def get(id: Int): F[Option[Set[Int]]] =
    for {
      x <- mvar.read
      v <- x.get(id).fold(F.pure[Option[Set[Int]]](None))(_.read.map(Some(_)))
    } yield v

  def sync(map: RDSMap[F]): F[Unit] =
    for {
      _ <- mvar.take
      _ <- mvar.put(map)
    } yield ()
}
