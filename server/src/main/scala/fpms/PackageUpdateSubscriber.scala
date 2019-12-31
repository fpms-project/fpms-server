package fpms

import java.util.concurrent.Executors
import cats.effect.ConcurrentEffect
import cats.effect.concurrent.MVar
import cats.implicits._
import fpms.VersionCondition._
import fs2.Stream
import fs2.concurrent.Queue
import fs2.concurrent.Topic
import org.slf4j.LoggerFactory
import scala.concurrent.ExecutionContext


class PackageUpdateSubscriber[F[_]](
  val name: String,
  containers: MVar[F, Seq[PackageDepsContainer[F]]],
  val queue: Queue[F, PackageUpdateEvent],
  topic: Topic[F, PackageUpdateEvent],
  val alreadySubscribed: MVar[F, Seq[String]]
)(
  implicit F: ConcurrentEffect[F]
) {
  private val logger = LoggerFactory.getLogger(this.getClass)
  implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(Executors.newCachedThreadPool())

  def deleteAllinQueue(): Unit = {
    while (F.toIO(queue.tryDequeue1).unsafeRunSync().isDefined) {}
  }

  def addNewVersion(container: PackageDepsContainer[F]): F[Unit] =
    for {
      _ <- F.pure(logger.info(s"new version:${container.info.name}@${container.info.version.original}"))
      newc <- containers.take.map(_ :+ container)
      _ <- containers.put(newc)
      deps <- container.dependencies
      _ <- topic.publish1(AddNewVersion(container.info, deps))
    } yield ()

  def getDependencies(condition: VersionCondition): F[Option[Seq[PackageInfo]]] =
    getLatestVersion(condition).flatMap(_.fold(F.pure[Option[Seq[PackageInfo]]](None))(e => e.dependencies.map(e => Some(e))))

  def getLatestVersion(condition: VersionCondition): F[Option[PackageDepsContainer[F]]] =
    containers.read.map(_.filter(e => condition.valid(e.info.version)).sortWith((x, y) => x.info.version > y.info.version).headOption)

  def onAddNewVersion(event: AddNewVersion): Stream[F, Unit] =
    readContainer
      .evalMap(c => {
        logger.info(s"[event]: Add new version in dep(${event.packageInfo.name}@${event.packageInfo.version.original}) at $name")
        c.addNewVersion(event.packageInfo, event.dependencies).map(result => (c, result))
      })
      .filter(_._2)
      .evalMap(x => x._1.dependencies.map(deps => UpdateDependency(x._1.info, deps)))
      .through(topic.publish)

  def onUpdateDependencies(event: UpdateDependency): Stream[F, Unit] =
    readContainer
      .evalMap(v => {
        logger.info(s"[event]: update deps in dep(${event.packageInfo.name}@${event.packageInfo.version.original}) at $name")
        v.updateDependencies(event.packageInfo, event.dependencies).map(result => (v, result))
      })
      .filter(_._2)
      .evalMap(x => x._1.dependencies.map(deps => UpdateDependency(x._1.info, deps)))
      .through(topic.publish)

  def readContainer: Stream[F, PackageDepsContainer[F]] =
    Stream.eval(containers.read).flatMap(x => Stream.apply(x: _*))

  def start: F[Unit] = queue.dequeue.flatMap(_ match {
    case e: AddNewVersion => onAddNewVersion(e)
    case e: UpdateDependency => onUpdateDependencies(e)
    case _ => Stream(())
  }).compile.drain
}
