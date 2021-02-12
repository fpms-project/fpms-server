package fpms.repository

// repositoryではないけどここが一番収まりがいいので
trait AddedPackageIdQueue[F[_]] {
  def push(id: Int): F[Unit]

  def popAll(): F[Seq[Int]]
}
