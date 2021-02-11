package fpms.repository

import fpms.LDIL.LDILMap

trait LDILRepository[F[_]] {
  def get(id: Int): F[Option[Seq[Int]]]

  def get(ids: Seq[Int]): F[LDILMap]

  def insert(id: Int, ldil: Seq[Int]): F[Unit]

  def insert(map: LDILMap): F[Unit]
}
