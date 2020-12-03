package fpms.calcurator.ldil
import cats.effect.concurrent.MVar
import cats.effect.Concurrent
import cats.implicits._

class LDILContainerOnMemory[F[_]](implicit F: Concurrent[F]) extends LDILContainer[F] {
  private val mvar = MVar.of[F, Map[Int, List[Int]]](Map.empty[Int, List[Int]])
  
  def get(id: Int): F[Option[Seq[Int]]] = {
    for {
      v <- mvar
      x <- v.read.map(_.get(id))
    } yield x
  }

  def sync(map: LDILMap): F[Unit] = mvar.flatMap(_.put(map))
}
