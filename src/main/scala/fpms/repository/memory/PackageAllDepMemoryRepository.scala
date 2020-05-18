package fpms.repository.memory

import cats.implicits._
import cats.effect.ConcurrentEffect
import cats.effect.concurrent.MVar
import fpms.{PackageAllDepRepository, PackageInfoBase}

class PackageAllDepMemoryRepository[F[_]](m: MVar[F, Map[PackageInfoBase, Seq[PackageInfoBase]]])(
    implicit F: ConcurrentEffect[F]
) extends PackageAllDepRepository[F] {
  override def get(pack: PackageInfoBase): F[Option[Seq[PackageInfoBase]]] = {
    m.read.map(_.get(pack))
  }

  override def store(pack: PackageInfoBase, deps: Seq[PackageInfoBase]): F[Unit] = {
    for {
      x <- m.take.map(_.updated(pack, deps))
      _ <- m.put(x)
    } yield ()
  }

  override def storeMultiEmpty(b: Seq[PackageInfoBase]): F[Unit] = {
    for {
      x <- m.take.map(_ ++ b.map((_, Seq.empty)).toMap)
      _ <- m.put(x)
    } yield ()
  }
}
