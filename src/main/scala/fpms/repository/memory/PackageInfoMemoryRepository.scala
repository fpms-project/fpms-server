package fpms.repository.memoryexception

import cats.effect.ConcurrentEffect
import fpms.{PackageInfo, PackageInfoBase, PackageInfoRepository}
import cats.effect.concurrent.MVar
import cats.implicits._

class PackageInfoMemoryRepository[F[_]](
    versionMap: MVar[F, Map[String, Seq[String]]],
    depMap: MVar[F, Map[PackageInfoBase, PackageInfo]]
)(implicit F: ConcurrentEffect[F])
    extends PackageInfoRepository[F] {

  override def store(info: PackageInfo): F[Unit] = {
    for {
      v <- depMap.take.map(v => v.updated(PackageInfoBase(info.name, info.version.toString()), info))
      _ <- depMap.put(v)
    } yield ()
  }

  override def storeVersions(infos: Seq[PackageInfo]): F[Unit] = {
    for {
      s <- versionMap.take.map(v => v.updated(infos.head.name, infos.map(_.version.toString())))
      _ <- versionMap.put(s)
      x <- depMap.take.map(x => x ++ infos.map(v => (PackageInfoBase(v.name, v.version.toString()) -> v)))
      _ <- depMap.put(x)
    } yield ()
  }

  override def get(name: String, version: String): F[Option[PackageInfo]] =
    depMap.read.map(v => v.get(PackageInfoBase(name, version)))

  override def getVersions(name: String): F[Option[Seq[PackageInfo]]] =
    for {
      vs <- versionMap.read.map(_.get(name))
      m <- depMap.read.map(mx => vs.map(versions => versions.map(v => mx.get(PackageInfoBase(name, v))).flatten))
    } yield m

  override def has(name: String): F[Boolean] = getVersions(name).map(_.isDefined)
}
