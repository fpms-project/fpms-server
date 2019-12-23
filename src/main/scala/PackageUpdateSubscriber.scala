package package_manager_server

import cats.effect.Concurrent
import cats.effect.concurrent.MVar
import cats.implicits._
import com.gilt.gfc.semver.SemVer
import fs2.Stream
import fs2.concurrent.Queue
import fs2.concurrent.Topic
import VersionCondition._


class PackageUpdateSubscriber[F[_]](createdTime: Long, containers: MVar[F, Seq[PackageDepsContainer[F]]], val queue: Queue[F, PackageUpdateEvent], topic: Topic[F, PackageUpdateEvent])(
  implicit F: Concurrent[F]
) {

  def addNewVersion(container: PackageDepsContainer[F]): F[Unit] =
    for {
      _ <- F.pure(println(s"new version:${container.info.name}@${container.info.version.original}"))
      newc <- containers.take.map(_ :+ container)
      _ <- containers.put(newc)
      deps <- container.dependencies
      _ <- topic.publish1(AddNewVersion(System.currentTimeMillis, container.info, deps))
    } yield ()

  def getDependencies(version: SemVer): F[Option[Seq[PackageInfo]]] = for {
    x <- containers.read.flatMap(_.find(p => p.info.version == version).fold(F.delay[Option[Seq[PackageInfo]]](None))(_.dependencies.map(e => Some(e))))
  } yield x

  def getLatestVersion(condition: VersionCondition): F[Option[PackageDepsContainer[F]]] =
    containers.read.map(_.filter(e => condition.valid(e.info.version)).sortWith((x, y) => x.info.version > y.info.version).headOption)

  def onAddNewVersion(event: AddNewVersion): Stream[F, Unit] =
    readContainer
      .evalMap(c => {
        c.addNewVersion(event.packageInfo, event.dependencies).map(result => (c, result))
      })
      .filter(_._2)
      .evalMap(x => x._1.dependencies.map(deps => UpdateDependency(System.currentTimeMillis, x._1.info, deps)))
      .through(topic.publish)

  def onUpdateDependencies(event: UpdateDependency): Stream[F, Unit] =
    readContainer
      .evalMap(v => v.updateDependencies(event.packageInfo, event.dependencies).map(result => (v, result)))
      .filter(_._2)
      .evalMap(x => x._1.dependencies.map(deps => UpdateDependency(System.currentTimeMillis, x._1.info, deps)))
      .through(topic.publish)

  def readContainer: Stream[F, PackageDepsContainer[F]] =
    Stream.eval(containers.read).flatMap(x => Stream.apply(x: _*))

  def start: F[Unit] = queue.dequeue.flatMap(_ match {
    case e: AddNewVersion if e.time > createdTime => onAddNewVersion(e)
    case e: UpdateDependency if e.time > createdTime => onUpdateDependencies(e)
    case _ => Stream(())
  }).compile.drain
}
