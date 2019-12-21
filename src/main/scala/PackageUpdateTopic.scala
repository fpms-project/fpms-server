package package_manager_server

sealed trait PackageUpdateEvent

case class AddNewVersion(packageInfo: PackageInfo, dependencies: Seq[PackageInfo]) extends PackageUpdateEvent

case class UpdateDependency(packageInfo: PackageInfo, dependencies: Seq[PackageInfo]) extends PackageUpdateEvent

case class AddNewNamePackage(name: String) extends PackageUpdateEvent
