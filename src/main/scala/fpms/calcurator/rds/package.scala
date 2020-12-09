package fpms.calcurator
import cats.effect.concurrent.MVar2

package object rds {
  private[rds] type RDSMap = Map[Int, scala.collection.Set[Int]]
}
