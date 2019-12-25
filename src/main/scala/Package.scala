package package_manager_server

import cats.effect._
import cats.effect.concurrent.MVar
import cats.implicits._
import com.gilt.gfc.semver.SemVer
import VersionCondition._

class PackageDepsContainer[F[_]](val info: PackageInfo, dep: MVar[F, Map[String, PackageInfo]], depPackages: MVar[F, Map[String, Seq[PackageInfo]]])(
  implicit F: Concurrent[F]
) {
  def dependencies: F[Seq[PackageInfo]] = for {
    mago <- depPackages.read.map(_.values.flatten[PackageInfo].toList)
    children <- dep.read.map(_.values.toList)
  } yield mago ++ children

  def addNewVersion(newPack: PackageInfo, deps: Seq[PackageInfo]): F[Boolean] = {
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

  def updateDependencies(updatedPack: PackageInfo, deps: Seq[PackageInfo]): F[Boolean] =
    dep.read.map(_.get(updatedPack.name)).flatMap {
      case Some(e) if e.version == updatedPack.version => for {
        x <- depPackages.take.map(_.updated(updatedPack.name, deps))
        _ <- depPackages.put(x)
      } yield true
      case _ => F.pure(false)
    }
}

case class PackageInfo(name: String, version: SemVer, dep: Map[String, VersionCondition])

