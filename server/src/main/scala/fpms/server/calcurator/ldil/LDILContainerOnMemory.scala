package fpms.server.calcurator.ldil

import cats.effect.ConcurrentEffect
import cats.effect.concurrent.MVar2
import cats.implicits._

class LDILContainerOnMemory[F[_]](mvar: MVar2[F, Map[Int, Seq[Int]]])(implicit F: ConcurrentEffect[F])
    extends LDILContainer[F] {

  def get(id: Int): F[Option[Seq[Int]]] = mvar.read.map(_.get(id))

  def sync(map: LDILMap): F[Unit] =
    for {
      _ <- mvar.tryTake
      _ <- mvar.put(map)
    } yield ()

  def update(subSet: Map[Int, List[Int]]): F[Unit] =
    for {
      x <- mvar.take
      _ <- mvar.put(x ++ subSet)
    } yield ()
}
