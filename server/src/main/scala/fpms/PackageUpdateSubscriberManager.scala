package fpms

import cats.Parallel
import cats.data.EitherT
import cats.data._
import cats.effect.ConcurrentEffect
import cats.effect.ContextShift
import cats.effect.concurrent.MVar
import cats.implicits._
import fpms.VersionCondition._
import fs2.concurrent.Queue

class PackageUpdateSubscriberManager[F[_] : ContextShift](
  subsmap: MVar[F, Map[String, PackageUpdateSubscriber[F]]], topicManager: TopicManager[F]
)(implicit f: ConcurrentEffect[F], P: Parallel[F]) {

  import PackageUpdateSubscriberManager._


  def addNewSubscriber(subscriber: PackageUpdateSubscriber[F]): F[Unit] =
    for {
      m <- subsmap.take.map(_.updated(subscriber.name, subscriber))
      _ <- subsmap.put(m)
    } yield ()

  def addNewPackage(pack: PackageInfo): EitherT[F, Any, Unit] = for {
    latest <- getLatestsOfDeps(pack.dep)
    deps <- calcuratePackageDependeincies(latest)
    d <- EitherT.right(MVar.of[F, Map[String, PackageInfo]](latest.mapValues(_.info)))
    x <- EitherT.right(MVar.of[F, Map[String, Seq[PackageInfo]]](deps))
    mp <- EitherT.right(subsmap.read.map(_.get(pack.name)))
    subscriber <- mp.fold(
      for {
        subs <- createNewSubscriber(pack)
        d <- EitherT.right[Any](subsmap.take.map(_.updated(pack.name, subs)))
        _ <- EitherT.right[Any](subsmap.put(d))
      } yield {
        f.toIO(subs.start).unsafeRunAsyncAndForget()
        subs
      })(e => EitherT.right(f.pure(e)))
    _ <- EitherT.rightT[F, Unit](pack.dep.keys.toSeq.foreach(d => f.toIO({
      subscriber.alreadySubscribed.take.flatMap(list => {
        if (!list.contains(d)) {
          subscriber.alreadySubscribed.put(list :+ d).flatMap(_ => topicManager.subscribeTopic(d, subscriber.queue))
        } else {
          f.unit
        }
      })
    }).unsafeRunAsyncAndForget()))
    _ <- EitherT.right(subscriber.addNewVersion(new PackageDepsContainer[F](pack, d, x)))
  } yield ()

  def getDependencies(name: String, version: VersionCondition): EitherT[F, PUSMError, DepResult] =
    for {
      m <- EitherT(subsmap.read.map(_.get(name).toRight(PackageNotFound)))  
      v <- EitherT(m.getDependencies(version).map(_.toRight[PUSMError](PackageVersionNotFound)))
    } yield v

  def getPackage(name: String): EitherT[F, PUSMError, Seq[PackageInfo]] =
    for {
      m <- EitherT(subsmap.read.map(_.get(name).toRight(PackageNotFound)))
      x <- EitherT.right(m.getAllVersion)
    } yield x

  def countPackageNames(): EitherT[F, PUSMError, Int] = EitherT.right(subsmap.read.map(_.size))

  def calcuratePackageDependeincies(latests: Map[String, PackageDepsContainer[F]]): EitherT[F, Nothing, Map[String, Seq[PackageInfo]]] =
    EitherT.right(
      latests.map(e => f.pure(e._1) product e._2.dependencies)
        .toList
        .toNel
        .map(_.parSequence.map(_.toList.toMap))
        .getOrElse(f.pure(Map.empty[String, Seq[PackageInfo]]))
    )

  def getLatestsOfDeps(deps: Map[String, String]): EitherT[F, PUSMError, Map[String, PackageDepsContainer[F]]] = {
    if (deps.isEmpty) {
      EitherT.rightT[F, PUSMError](Map.empty[String, PackageDepsContainer[F]])
    } else {
      val list = deps.map(
        e => for {
          v <- OptionT(subsmap.read.map(_.get(e._1)))
          d <- OptionT(v.getLatestVersion(e._2))
        } yield d
      ).toList.toNel.get
      list.parSequence.map(e => e.toList.map(e => (e.info.name, e)).toMap).toRight(CantGetLatestOfDeps)
    }
  }


  def createNewSubscriber(pack: PackageInfo): EitherT[F, PUSMError, PackageUpdateSubscriber[F]] =
    EitherT(
      for {
        queue <- Queue.bounded[F, PackageUpdateEvent](100)
        topic <- topicManager.addNewNamePackage(pack.name)
        mv <- MVar.of[F, Seq[PackageDepsContainer[F]]](Seq.empty)
        already <- MVar.of[F, Seq[String]](Seq.empty)
      } yield Right(new PackageUpdateSubscriber[F](pack.name, mv, queue, topic, already))
    )
}


object PackageUpdateSubscriberManager {

  // TODO: error ハンドリングまともにする
  sealed trait PUSMError

  case object CantCreateNewSubscriber extends PUSMError

  case object DepPackageNotFound extends PUSMError

  case object DepPackageVersionNotFound extends PUSMError

  case object CantGetLatestOfDeps extends PUSMError

  case object PackageNotFound extends PUSMError

  case object PackageVersionNotFound extends PUSMError


  import io.circe._

  implicit val encode: Encoder[PUSMError] = Encoder.instance(a => Json.fromString(a.toString))

}
