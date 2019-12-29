package fpms

import cats.Parallel
import cats.data.EitherT
import cats.effect.ConcurrentEffect
import cats.effect.ContextShift
import cats.effect.concurrent.MVar
import cats.syntax.all._
import VersionCondition._
import fs2.concurrent.Queue

class PackageUpdateSubscriberManager[F[_] : ContextShift](
  map: MVar[F, Map[String, PackageUpdateSubscriber[F]]], topicManager: TopicManager[F]
)(implicit f: ConcurrentEffect[F], P: Parallel[F]) {

  import PackageUpdateSubscriberManager._

  def addNewPackage(pack: PackageInfo): EitherT[F, Any, Unit] = for {
    latest <- getLatestsOfDeps(pack.dep)
    deps <- calcuratePackageDependeincies(latest)
    d <- EitherT.right(MVar.of[F, Map[String, PackageInfo]](latest.mapValues(_.info)))
    x <- EitherT.right(MVar.of[F, Map[String, Seq[PackageInfo]]](deps))
    mp <- EitherT.right(map.read.map(_.get(pack.name)))
    subscriber <- mp.fold(
      for {
        subs <- createNewSubscriber(pack)
        d <- EitherT.right[Any](map.take.map(_.updated(pack.name, subs)))
        _ <- EitherT.right[Any](map.put(d))
      } yield {
        f.toIO(subs.start).unsafeRunAsyncAndForget()
        subs
      })(e => EitherT.right(f.pure(e)))
    _ <- EitherT.rightT[F, Unit](pack.dep.keys.toSeq.foreach(d => f.toIO(topicManager.subscribeTopic(d, subscriber.queue)).unsafeRunAsyncAndForget()))
    _ <- EitherT.right(subscriber.addNewVersion(new PackageDepsContainer[F](pack, d, x)))
  } yield ()

  def getDependencies(name: String, version: VersionCondition): EitherT[F, PUSMError, Seq[PackageInfo]] =
    EitherT(
      map.read
        .map(_.get(name))
        .flatMap(c =>
          c.map[F[Either[PUSMError, Seq[PackageInfo]]]](
            _.getDependencies(version).map(_.toRight(CantGetLatestOfDeps))
          ).getOrElse[F[Either[PUSMError, Seq[PackageInfo]]]](f.pure(Left(CantGetLatestOfDeps))))
    )


  def calcuratePackageDependeincies(latests: Map[String, PackageDepsContainer[F]]): EitherT[F, Nothing, Map[String, Seq[PackageInfo]]] =
    EitherT.right(
      latests.map(e => f.pure(e._1) product e._2.dependencies)
        .toList
        .toNel
        .map(_.parSequence.map(_.toList.toMap))
        .getOrElse(f.pure(Map.empty[String, Seq[PackageInfo]]))
    )

  def getLatestsOfDeps(deps: Map[String, String]): EitherT[F, PUSMError, Map[String, PackageDepsContainer[F]]] =
    EitherT(
      deps.map(
        e => for {
          v <- map.read.map(_.get(e._1))
          d <- f.pure(v).flatMap[Option[PackageDepsContainer[F]]](_.map(_.getLatestVersion(e._2)).getOrElse(f.pure(None)))
        } yield d
      ).toList.toNel.fold(f.delay[Either[PUSMError, Map[String, PackageDepsContainer[F]]]](Right(Map.empty[String, PackageDepsContainer[F]])))(list =>
        list.parSequence.map(e => {
          if (e.forall(_.isDefined)) {
            Right(e.toList.flatMap(_.map(v => (v.info.name, v))).toMap)
          } else {
            Left(CantGetLatestOfDeps)
          }
        })
      )
    )


  def createNewSubscriber(pack: PackageInfo): EitherT[F, PUSMError, PackageUpdateSubscriber[F]] =
    EitherT(
      for {
        queue <- Queue.bounded[F, PackageUpdateEvent](100)
        topic <- topicManager.addNewNamePackage(pack)
        mv <- MVar.of[F, Seq[PackageDepsContainer[F]]](Seq.empty)
      } yield Right(new PackageUpdateSubscriber[F](System.currentTimeMillis, mv, queue, topic))
    )
}

object PackageUpdateSubscriberManager {

  sealed trait PUSMError

  case object CantCreateNewSubscriber extends PUSMError

  case object CantGetLatestOfDeps extends PUSMError

  import io.circe._

  implicit val encode: Encoder[PUSMError] = Encoder.instance(a => Json.fromString(a.toString))

}
