package fpms.calcurator

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
  def get(id: Int): Option[PackageCalcuratedDeps]

  /**
    * load data
    */
  def load(): Unit

  /**
    * add datas
    *
    * @param added
    */
  def add(addPackage: AddPackage): Unit
}

case class AddPackage(name: String, version: String, deps: Map[String, String])

case class PackageNode(
    src: Int,
    directed: Seq[Int],
    packages: scala.collection.mutable.Set[Int]
)

case class PackageCalcuratedDeps(
    direct: Seq[Int],
    all: Set[Int]
)
