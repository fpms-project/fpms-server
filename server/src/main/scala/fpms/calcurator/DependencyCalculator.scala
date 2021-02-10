package fpms.calcurator

trait DependencyCalculator[F[_]] {

  /**
    * initalize data
    */
  def initialize(): F[Unit]

  /**
    * get package data
    *
    * @param id
    * @return
    */
  def get(id: Int): F[Option[PackageCalcuratedDeps]]

  /**
    * add datas
    *
    * @param added
    */
  def add(addPackage: AddPackage): F[Unit]
}

case class AddPackage(name: String, version: String, deps: Map[String, String])

case class PackageCalcuratedDeps(
    direct: Seq[Int],
    all: Set[Int]
)
