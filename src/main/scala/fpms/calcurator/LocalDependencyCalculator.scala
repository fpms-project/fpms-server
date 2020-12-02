package fpms.calcurator

import com.typesafe.scalalogging.LazyLogging

class LocalDependencyCalculator extends DependencyCalculator with LazyLogging {
  private val allDepsCalcurator:RDSCalculator =  new RDSCalculator()

  def initialize(): Unit = {
    setup()
  }

  def getAll = allDepsCalcurator.getAll

  def get(id: Int): Option[PackageCalcuratedDeps] = allDepsCalcurator.get(id)

  /**
    * WARNING: same as initilalize
    */
  def load(): Unit = initialize()

  def add(added: AddPackage): Unit = {}

  private def setup(): Unit = {
    logger.info("start setup")
    val idMap =JsonLDILMapGenerator.gen
    System.gc()
    allDepsCalcurator.calcAllDep(idMap)
  }
}
