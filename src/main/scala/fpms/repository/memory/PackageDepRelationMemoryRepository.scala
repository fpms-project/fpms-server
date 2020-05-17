package fpms.repository.memory

import cats.implicits._
import cats.effect.ConcurrentEffect
import cats.effect.concurrent.MVar
import fpms.{PackageDepRelationRepository, PackageInfoBase}

class PackageDepRelationMemoryRepository[F[_]](m: MVar[F, Map[String, Seq[PackageInfoBase]]])(
    implicit F: ConcurrentEffect[F]
) extends PackageDepRelationRepository[F] {

  override def add(name: String, info: PackageInfoBase): F[Unit] = {
    for {
      v <-
        m.take.map(ma => ma.updated(name, ma.getOrElse(name, Seq.empty[PackageInfoBase]) :+ info))
      _ <- m.put(v)
    } yield ()
  }

  override def get(name: String): F[Option[Seq[PackageInfoBase]]] =
    m.read.map(_.get(name))
}
