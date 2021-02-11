package fpms.repository

import fpms.RDS.RDSMap

trait RDSRepository[F[_]] {
  def get(id: Int): F[Option[Set[Int]]]

  def insert(id: Int, rds: Set[Int]): F[Unit]

  def insert(map: RDSMap): F[Unit]
}
