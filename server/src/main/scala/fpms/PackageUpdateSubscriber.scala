package fpms

import java.util.concurrent.Executors
import cats.effect.Concurrent
import cats.effect.concurrent.MVar
import cats.implicits._
import VersionCondition._
import fs2.Stream
import fs2.concurrent.Queue
import fs2.concurrent.Topic
import scala.concurrent.ExecutionContext


class PackageUpdateSubscriber[F[_]](createdTime: Long, containers: MVar[F, Seq[PackageDepsContainer[F]]], val queue: Queue[F, PackageUpdateEvent], topic: Topic[F, PackageUpdateEvent])(
  implicit F: Concurrent[F]
) {
  implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(Executors.newCachedThreadPool())

  def addNewVersion(container: PackageDepsContainer[F]): F[Unit] =
    for {
      _ <- F.pure(println(s"new version:${container.info.name}@${container.info.version.original}"))
      newc <- containers.take.map(_ :+ container)
      _ <- containers.put(newc)
      deps <- container.dependencies
      _ <- topic.publish1(AddNewVersion(System.currentTimeMillis, container.info, deps))
    } yield ()

  def getDependencies(condition: VersionCondition): F[Option[Seq[PackageInfo]]] =
    getLatestVersion(condition).flatMap(_.fold(F.pure[Option[Seq[PackageInfo]]](None))(e => e.dependencies.map(e => Some(e))))

  def getLatestVersion(condition: VersionCondition): F[Option[PackageDepsContainer[F]]] =
    containers.read.map(_.filter(e => condition.valid(e.info.version)).sortWith((x, y) => x.info.version > y.info.version).headOption)

  def onAddNewVersion(event: AddNewVersion): Stream[F, Unit] =
    readContainer
      .evalMap(c => {
        println(s"[event]: Add new version in dep(${event.packageInfo.name})")
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