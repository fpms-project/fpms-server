package fpms.calcurator.rds

import cats.effect.ConcurrentEffect
import cats.effect.concurrent.MVar2
import cats.implicits._

class RDSContainerOnMemory[F[_]](mvar: MVar2[F, RDSMap])(implicit F: ConcurrentEffect[F]) extends RDSContainer[F] {

  def get(id: Int): F[Option[scala.collection.Set[Int]]] = mvar.read.map(_.get(id).map(_.toSet))

  def sync(map: RDSMap): F[Unit] =
    for {
      _ <- mvar.take
      _ <- mvar.put(map)
    } yield ()
}
