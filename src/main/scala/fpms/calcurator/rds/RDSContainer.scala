package fpms.calcurator.rds

trait RDSContainer[F[_]] {
  def get(id: Int): F[Option[Set[Int]]]

  def sync(map: RDSMap): F[Unit]
}
