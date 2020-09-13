package fpms

trait DependencyCalculator {

  /**
    * initalize data
    */
  def initialize(): Unit
  /**
    * get package data
    *
    * @param id
    * @return
    */
  def get(id: Int): Option[PackageNode]
  /**
    * load data
    */
  def load(): Unit
  /**
    * add datas
    *
    * @param added
    */
  def add(added: Seq[SourcePackageInfo]): Unit
}

case class PackageNode(
    src: Int,
    directed: Seq[Int],
    packages: scala.collection.mutable.Set[Int]
)

case class PackageNodeRespose(
    src: SourcePackage,
    directed: Seq[SourcePackage],
    packages: Set[SourcePackage]
)
