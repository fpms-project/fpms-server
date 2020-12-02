package fpms.calcurator.ldil

trait LDILRepository[F[_]] {

  def get(id: Int): F[Option[Seq[Int]]]

  def sync(map: LDILMap): F[Unit]
}
