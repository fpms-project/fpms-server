package fpms.calcurator.rds

import cats.effect.ConcurrentEffect
import cats.effect.concurrent.MVar
import cats.effect.concurrent.MVar2
import cats.implicits._

class RDSContainerOnMemory[F[_]](implicit F: ConcurrentEffect[F]) extends RDSContainer[F] {
  private val mvar = F.toIO(MVar.of[F, RDSMap](Map.empty[Int, Set[Int]])).unsafeRunSync()

  def get(id: Int): F[Option[scala.collection.Set[Int]]] = mvar.read.map(_.get(id))

  def sync(map: RDSMap): F[Unit] =
    for {
      _ <- mvar.take
      _ <- mvar.put(map)
    } yield ()
}
