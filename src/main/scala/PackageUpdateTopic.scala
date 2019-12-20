package package_manager_server

import com.gilt.gfc.semver.SemVer

sealed trait PackageUpdateEvent

case class AddNewVersion(name: String,version: SemVer,dependency: Seq[PackageBase]) extends PackageUpdateEvent

case class UpdateDependency(name: String,version: SemVer, dependencies: Seq[PackageBase]) extends PackageUpdateEvent
