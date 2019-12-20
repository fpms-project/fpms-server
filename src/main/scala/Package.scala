package package_manager_server

import cats.MonadError
import com.gilt.gfc.semver.SemVer
import cats.effect.concurrent.MVar
import scala.collection.mutable.Map
import cats.effect._
import cats.effect.concurrent._
import cats.syntax.all._


case class PackageBase(name: String, version: SemVer)

// TODO: Think about thread safe
case class CodePackage[F[_] : ConcurrentEffect](info: PackageBase, dep: MVar[F, Map[String, (VersionCondition, SemVer)]], depPackages: MVar[F, Map[String, Seq[PackageBase]]]) {
  def dependencies: F[Seq[PackageBase]] = depPackages.read.map(_.values.flatten[PackageBase].toList)

  def updateDep(name: String, version: SemVer, deps: Seq[PackageBase]): F[Boolean] =
    dep.read.map(_.get(name)).collect {}

}
