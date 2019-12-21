package package_manager_server

import cats.effect.ConcurrentEffect
import cats.effect.concurrent.MVar
import cats.implicits._
import com.gilt.gfc.semver.SemVer
import fs2.Stream
import fs2.concurrent.Queue
import fs2.concurrent.Topic


class PackageUpdateSubscriber[F[_]](containers: MVar[F, Seq[PackageDepsContainer[F]]], queue: Queue[F, PackageUpdateEvent], topic: Topic[F, PackageUpdateEvent])(
  implicit F: ConcurrentEffect[F]
) {
  def onAddNewVersion(event: AddNewVersion): Stream[F, Unit] =
    readContainer
      .evalMap(c => c.addNewVersion(event.packageInfo, event.dependencies).map(result => (c, result)))
      .filter(_._2)
      .evalMap(x => x._1.dependencies.map(deps => UpdateDependency(x._1.info, deps)))
      .through(topic.publish)

  def onUpdateDependencies(event: UpdateDependency): Stream[F, Unit] =
    readContainer
      .evalMap(v => v.updateDependencies(event.packageInfo, event.dependencies).map(result => (v, result)))
      .filter(_._2)
      .evalMap(x => x._1.dependencies.map(deps => UpdateDependency(x._1.info, deps)))
      .through(topic.publish)

  def addNewVersion(container: PackageDepsContainer[F]): Stream[F, Unit] =
    Stream.eval(containers.take).map(_ :+ container)
      .evalMap(r => for {
        _ <- containers.put(r)
        deps <- container.dependencies
        _ <- topic.publish1(AddNewVersion(container.info, deps))
      } yield ())

  def getDependencies(version: SemVer): F[Option[Seq[PackageInfo]]] = for {
    x <- containers.read.flatMap(_.find(p => p.info.version == version).fold(F.delay[Option[Seq[PackageInfo]]](None))(_.dependencies.map(e => Some(e))))
  } yield x

  def readContainer: Stream[F, PackageDepsContainer[F]] =
    Stream.eval(containers.read).flatMap(x => Stream.apply(x: _*))

  def start: Stream[F, Unit] = queue.dequeue.flatMap(_ match {
    case e: AddNewVersion => onAddNewVersion(e)
    case e: UpdateDependency => onUpdateDependencies(e)
    case _ => Stream(())
  })
}
