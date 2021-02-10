package fpms.calcurator.ldil

trait LDILContainer[F[_]] {

  def get(id: Int): F[Option[Seq[Int]]]

  def sync(map: LDILMap): F[Unit]

  def update(subSet: Map[Int, List[Int]]): F[Unit]
}
