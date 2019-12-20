package package_manager_server

import com.gilt.gfc.semver.SemVer


case class PackageBase(name: String, version: SemVer)

case class Dependency(name:String, versionCondition: String)

case class CodePackage(info: PackageBase, dependencies: Seq[Dependency])

