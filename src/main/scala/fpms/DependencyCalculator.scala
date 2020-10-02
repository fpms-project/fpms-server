package fpms

import com.github.sh4869.semver_parser.SemVer

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
  def add(addPackage: AddPackage): Unit
}

case class PackageNode(
    src: Int,
    directed: Seq[Int],
    packages: scala.collection.mutable.Set[Int]
)

case class PackageNodeRespose(
    src: SourcePackageSave,
    directed: Seq[SourcePackageSave],
    packages: Set[SourcePackageSave]
)
