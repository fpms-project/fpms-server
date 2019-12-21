package package_manager_server

import cats.Parallel
import cats.data.NonEmptyList
import cats.effect.Concurrent
import cats.effect.ContextShift
import cats.effect.concurrent.MVar
import cats.syntax.all._
import com.gilt.gfc.semver.SemVer
import fs2.concurrent.Queue
import VersionCondition._
import cats.effect.ConcurrentEffect

class PackageUpdateSubscriberManager[F[_] : ContextShift](map: MVar[F, Map[String, PackageUpdateSubscriber[F]]], topicManager: TopicManager[F])(implicit f: ConcurrentEffect[F], P: Parallel[F]) {

  def addNewPackage(pack: PackageInfo) = for {
    mp <- map.read.map(_.get(pack.name))
    subscriber <- mp match {
      case Some(e) => f.pure(e)
      case None => for {
        subs <- createNewSubscriber(pack)
        d <- map.take.map(_.updated(pack.name, subs))
        _ <- map.put(d)
      } yield {
        f.toIO(subs.start).unsafeRunAsyncAndForget()
        subs
      }
    }
    latest <- getLatestsOfDeps(pack.dep)
    deps <- calcuratePackageDependeincies(latest)
    d <- MVar.of[F, Map[String, PackageInfo]](latest.mapValues(_.info))
    x <- MVar.of[F, Map[String, Seq[PackageInfo]]](deps)
    _ <- f.pure(pack.dep.keys.foreach(d => f.toIO(topicManager.subscribeTopic(d, subscriber.queue)).unsafeRunAsyncAndForget()))
    _ <- subscriber.addNewVersion(new PackageDepsContainer[F](pack, d, x))
  } yield ()

  def getDependencies(name: String, version: SemVer): F[Option[Seq[PackageInfo]]] =
    map.read.map(_.get(name)).flatMap(_.map(_.getDependencies(version)).getOrElse(f.raiseError(new Throwable(s"${name}@${version} package not found"))))

  def calcuratePackageDependeincies(latests: Map[String, PackageDepsContainer[F]]) = NonEmptyList.fromList(
    latests.map(e => f.pure(e._1) product e._2.dependencies).toList).map(_.parSequence.map(_.toList.toMap)).getOrElse(f.pure(Map.empty[String, Seq[PackageInfo]]))

  def getLatestsOfDeps(deps: Map[String, VersionCondition]) = NonEmptyList.fromList(deps.map(e => for {
    v <- map.read.map(_.get(e._1))
    d <- v.fold(f.raiseError[PackageDepsContainer[F]](new Throwable(s"${e._1} package not found")))(
      subscriber =>
        subscriber.getLatestVersion(e._2).flatMap(_.fold(f.raiseError[PackageDepsContainer[F]](new Throwable(s"${e._1} package not found")))(e => f.pure(e)))
    )
  } yield d).toList).map(_.parSequence.map(e => e.toList.map(e => (e.info.name, e)).toMap)).getOrElse(f.pure(Map.empty[String, PackageDepsContainer[F]]))

  def createNewSubscriber(pack: PackageInfo) = for {
    queue <- Queue.bounded[F, PackageUpdateEvent](100)
    topic <- topicManager.addNewNamePackage(pack)
    mv <- MVar.of[F, Seq[PackageDepsContainer[F]]](Seq.empty)
  } yield new PackageUpdateSubscriber[F](mv, queue, topic)
}
