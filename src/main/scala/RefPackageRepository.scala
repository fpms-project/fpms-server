
package package_manager_server

import com.gilt.gfc.semver.SemVer


trait RefPackageRepository {
  def get(name: String): Seq[Seq[RefPackage]]
  def getAll: Map[String,Seq[RefPackage]]
  def add(parent: String,name: String, refPackage: RefPackage): Option[Unit]
}

case class RefPackage(version: SemVer, versionCondition: String)
