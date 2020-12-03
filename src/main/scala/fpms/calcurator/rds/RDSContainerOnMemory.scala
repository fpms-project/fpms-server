package fpms.calcurator.rds

import cats.effect.concurrent.MVar
import cats.implicits._
import cats.effect.ConcurrentEffect

class RDSContainerOnMemory[F[_]](implicit F: ConcurrentEffect[F]) extends RDSContainer[F] {
  private val mvar = F.toIO(MVar.of[F, RDSMap](Map.empty[Int, Set[Int]])).unsafeRunSync()

  def get(id: Int): F[Option[Set[Int]]] = mvar.read.map(_.get(id))

  def sync(map: RDSMap): F[Unit] =
    for {
      _ <- mvar.take
      _ <- mvar.put(map)
    } yield ()
}
