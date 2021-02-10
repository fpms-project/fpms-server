package fpms.server.calcurator.rds

trait RDSContainer[F[_]] {
  def get(id: Int): F[Option[scala.collection.Set[Int]]]

  def sync(map: RDSMap): F[Unit]
}
