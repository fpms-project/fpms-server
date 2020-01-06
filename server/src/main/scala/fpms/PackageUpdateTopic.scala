package fpms

sealed trait PackageUpdateEvent

case class AddNewVersion(packageInfo: PackageInfo, dependencies: Seq[PackageDepInfo]) extends PackageUpdateEvent

case class UpdateDependency(packageInfo: PackageInfo, dependencies: Seq[PackageDepInfo]) extends PackageUpdateEvent

case class AddNewNamePackage(name: String) extends PackageUpdateEvent
