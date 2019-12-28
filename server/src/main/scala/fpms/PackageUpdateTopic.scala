package fpms

sealed trait PackageUpdateEvent

case class AddNewVersion(time: Long,packageInfo: PackageInfo, dependencies: Seq[PackageInfo]) extends PackageUpdateEvent

case class UpdateDependency(time: Long,packageInfo: PackageInfo, dependencies: Seq[PackageInfo]) extends PackageUpdateEvent

case class AddNewNamePackage(name: String) extends PackageUpdateEvent
