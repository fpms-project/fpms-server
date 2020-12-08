package fpms.calcurator
import cats.effect.concurrent.MVar2

package object rds {
  private[rds] type RDSMap[F[_]] = Map[Int, MVar2[F, Set[Int]]]
}
