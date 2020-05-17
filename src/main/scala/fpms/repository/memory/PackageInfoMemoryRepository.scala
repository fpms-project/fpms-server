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
      v <- depMap.take.map(v => v.updated(PackageInfoBase(info.name, info.version), info))
      _ <- depMap.put(v)
    } yield ()
  }

  override def storeVersions(name: String, versions: Seq[String]): F[Unit] = {
    for {
      s <- versionMap.take.map(v => v.updated(name, versions))
      _ <- versionMap.put(s)
    } yield ()
  }

  override def get(name: String, version: String): F[Option[PackageInfo]] =
    depMap.read.map(v => v.get(PackageInfoBase(name, version)))

  override def getVersions(name: String): F[Option[Seq[String]]] =
    versionMap.read.map(v => v.get(name))

  override def has(name: String): F[Boolean] = getVersions(name).map(_.isDefined)
}
