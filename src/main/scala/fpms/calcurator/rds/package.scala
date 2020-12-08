package fpms.calcurator
import cats.effect.concurrent.MVar

package object rds {
  private[rds] type RDSMap[F[_]] = Map[Int, MVar[F, Set[Int]]]
}
