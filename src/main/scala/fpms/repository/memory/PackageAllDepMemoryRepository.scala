package fpms.repository.memory

import cats.implicits._
import cats.effect.ConcurrentEffect
import cats.effect.concurrent.MVar
import fpms.{PackageAllDepRepository, PackageInfoBase}
import fpms.PackageInfo

class PackageAllDepMemoryRepository[F[_]](
    m: MVar[F, Map[PackageInfoBase, Map[String, Seq[PackageInfoBase]]]]
)(implicit
    F: ConcurrentEffect[F]
) extends PackageAllDepRepository[F] {
  override def get(name: String, version: String): F[Option[Map[String, Seq[PackageInfoBase]]]] = {
    m.read.map(_.get(PackageInfoBase(name, version)))
  }

  override def store(pack: PackageInfoBase, deps: Map[String, Seq[PackageInfoBase]]): F[Unit] = {
    for {
      x <- m.take.map(_.updated(pack, deps))
      _ <- m.put(x)
    } yield ()
  }

  override def storeMultiEmpty(b: Seq[PackageInfoBase]): F[Unit] = {
    for {
      x <- m.take.map(_ ++ b.map(v => (v, Map.empty[String, Seq[PackageInfoBase]])).toMap)
      _ <- m.put(x)
    } yield ()
  }

  override def count(): F[Int] = m.read.map(_.values.size)

}
