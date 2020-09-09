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
import com.github.sh4869.semver_parser.Range
import scala.util.Try

class PackageUpdateSubscriberManager[F[_]: ContextShift](
    subsmap: MVar[F, Map[String, PackageUpdateSubscriber[F]]],
    topicManager: TopicManager[F]
)(implicit f: ConcurrentEffect[F], P: Parallel[F]) {

  import PackageUpdateSubscriberManager._

  def addNewSubscriber(subscriber: PackageUpdateSubscriber[F]): F[Unit] =
    for {
      m <- subsmap.take.map(_.updated(subscriber.name, subscriber))
      _ <- subsmap.put(m)
    } yield ()

  def addNewPackage(pack: PackageInfo): EitherT[F, Any, Unit] =
    for {
      latest <- getLatestsOfDeps(pack.dep)
      deps <- calcuratePackageDependeincies(latest)
      d <- EitherT.right(MVar.of[F, Map[String, PackageInfo]](latest.mapValues(_.info)))
      x <- EitherT.right(MVar.of[F, Map[String, Seq[PackageDepInfo]]](deps))
      mp <- EitherT.right(subsmap.read.map(_.get(pack.name)))
      subscriber <- mp.fold(for {
        subs <- createNewSubscriber(pack)
        d <- EitherT.right[Any](subsmap.take.map(_.updated(pack.name, subs)))
        _ <- EitherT.right[Any](subsmap.put(d))
      } yield {
        f.toIO(subs.start).unsafeRunAsyncAndForget()
        subs
      })(e => EitherT.right(f.pure(e)))
      _ <- EitherT.rightT[F, Unit](
        pack.dep.keys.toSeq.foreach(d =>
          f.toIO({
              subscriber.alreadySubscribed.take.flatMap(list => {
                if (!list.contains(d)) {
                  subscriber.alreadySubscribed
                    .put(list + d)
                    .flatMap(_ => topicManager.subscribeTopic(d, subscriber.queue))
                } else {
                  f.unit
                }
              })
            })
            .unsafeRunAsyncAndForget()
        )
      )
      _ <- EitherT.right(subscriber.addNewVersion(new PackageDepsContainer[F](pack, d, x)))
    } yield ()

  def getMultiDependencies(request: Seq[RequestCondition]): EitherT[F, PUSMError, MultiPackageResult] = {
    if (request.isEmpty) {
      EitherT.rightT(MultiPackageResult(Seq.empty, Map.empty))
    } else
      request
        .map(r => getDependencies(r.name, Range(r.condition)).toOption)
        .toList
        .toNel
        .get
        .parSequence
        .map(l => MultiPackageResult(l.map(_.pack).toList, l.toList.map(e => (e.pack.name, e.deps)).toMap))
        .toRight(PackageNotFound)

  }

  def getDependencies(name: String, version: Range): EitherT[F, PUSMError, DepResult] =
    for {
      m <- EitherT(subsmap.read.map(_.get(name).toRight(PackageNotFound)))
      v <- EitherT(m.getDependencies(version).map(_.toRight[PUSMError](PackageVersionNotFound)))
    } yield v

  def getPackage(name: String): EitherT[F, PUSMError, Seq[PackageInfo]] =
    for {
      m <- EitherT(subsmap.read.map(_.get(name).toRight(PackageNotFound)))
      x <- EitherT.right(m.getAllVersion)
    } yield x

  def getPackages(names: Seq[String]): EitherT[F, PUSMError, Map[String, Seq[PackageInfo]]] =
    if (names.isEmpty) {
      EitherT.rightT(Map.empty)
    } else {
      names
        .map(name => getPackage(name).toOption)
        .toList
        .toNel
        .get
        .parSequence
        .map(_.toList.map(e => (e.head.name, e)).toMap)
        .toRight(PackageNotFound)
    }

  def countPackageNames(): EitherT[F, PUSMError, Int] = EitherT.right(subsmap.read.map(_.size))

  def calcuratePackageDependeincies(
      latests: Map[String, PackageDepsContainer[F]]
  ): EitherT[F, Nothing, Map[String, Seq[PackageDepInfo]]] =
    EitherT.right(
      latests
        .map(e => f.pure(e._1) product e._2.dependencies)
        .toList
        .toNel
        .map(_.parSequence.map(_.toList.toMap))
        .getOrElse(f.pure(Map.empty[String, Seq[PackageDepInfo]]))
    )

  def getLatestsOfDeps(deps: Map[String, String]): EitherT[F, PUSMError, Map[String, PackageDepsContainer[F]]] = {
    if (deps.isEmpty) {
      EitherT.rightT[F, PUSMError](Map.empty[String, PackageDepsContainer[F]])
    } else {
      val list = deps
        .map(e => {
          Try {
            val range = Range(e._2)
            for {
              v <- OptionT(subsmap.read.map(_.get(e._1)))
              d <- OptionT(v.getLatestVersion(range))
            } yield d
          }.getOrElse(OptionT.none[F, PackageDepsContainer[F]])
        })
        .toList
      list.parSequence.map(e => e.toList.map(e => (e.info.name, e)).toMap).toRight(CantGetLatestOfDeps)
    }
  }

  def createNewSubscriber(pack: PackageInfo): EitherT[F, PUSMError, PackageUpdateSubscriber[F]] =
    EitherT(
      for {
        queue <- Queue.bounded[F, PackageUpdateEvent](100)
        topic <- topicManager.addNewNamePackage(pack.name)
        mv <- MVar.of[F, Set[PackageDepsContainer[F]]](Set.empty)
        already <- MVar.of[F, Set[String]](Set(pack.name))
      } yield Right(new PackageUpdateSubscriber[F](pack.name, mv, queue, topic, already))
    )
}

case class RequestCondition(name: String, condition: String)

case class MultiPackageResult(packs: Seq[PackageDepInfo], deps: Map[String, Seq[PackageDepInfo]])

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