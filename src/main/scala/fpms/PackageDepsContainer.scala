package fpms

import cats.effect._
import cats.effect.concurrent.MVar
import cats.implicits._

class PackageDepsContainer[F[_]](val info: PackageInfo, dep: MVar[F, Map[String, PackageInfo]], depPackages: MVar[F, Map[String, Seq[PackageDepInfo]]])(
  implicit F: Concurrent[F]
) {

  import VersionCondition._

  def packdepInfo: F[PackageDepInfo] = for {
    child <- dep.read.map(_.values.map(e => (e.name, e.version)).toMap)
  } yield PackageDepInfo(info.name, info.version, child)

  def dependencies: F[Seq[PackageDepInfo]] = for {
    mago <- depPackages.read.map(_.values.flatten[PackageDepInfo].toList)
    info <- packdepInfo
  } yield info +: mago

  def addNewVersion(newPack: PackageInfo, deps: Seq[PackageDepInfo]): F[Boolean] = {
    if (info.dep.get(newPack.name).exists(_.valid(newPack.version))) {
      dep.read.map(_.get(newPack.name)).flatMap {
        case Some(e) if e.version < newPack.version => for {
          m <- dep.take.map(_.updated(newPack.name, newPack))
          _ <- dep.put(m)
          x <- depPackages.take.map(_.updated(newPack.name, deps))
          _ <- depPackages.put(x)
        } yield true
        case _ => F.pure(false)
      }
    } else {
      F.pure(false)
    }
  }

  def updateDependencies(updatedPack: PackageInfo, deps: Seq[PackageDepInfo]): F[Boolean] =
    dep.read.map(_.get(updatedPack.name)).flatMap {
      case Some(e) if e.version == updatedPack.version => for {
        x <- depPackages.take.map(_.updated(updatedPack.name, deps))
        _ <- depPackages.put(x)
      } yield true
      case _ => F.pure(false)
    }

  override def equals(obj: Any): Boolean = obj match {
    case p: PackageDepsContainer[F] => p.info == this.info
    case _ => false
  }
}

