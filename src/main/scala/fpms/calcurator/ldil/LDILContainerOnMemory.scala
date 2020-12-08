package fpms.calcurator.ldil
import cats.effect.concurrent.MVar
import cats.implicits._
import cats.effect.ConcurrentEffect

class LDILContainerOnMemory[F[_]](implicit F: ConcurrentEffect[F]) extends LDILContainer[F] {
  private val mvar = F.toIO(MVar.of[F, Map[Int, List[Int]]](Map.empty[Int, List[Int]])).unsafeRunSync()

  def get(id: Int): F[Option[Seq[Int]]] = mvar.read.map(_.get(id))

  def sync(map: LDILMap): F[Unit] =
    for {
      _ <- mvar.tryTake
      _ <- mvar.put(map)
    } yield ()
}
