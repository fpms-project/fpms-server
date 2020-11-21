package fpms.calcurator

import com.typesafe.scalalogging.LazyLogging

class LocalDependencyCalculator extends DependencyCalculator with LazyLogging {
  private val allDepsCalcurator:AllDepsCalcurator =  new AllDepsCalcurator()

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

  // TODO: これをそもそも別classに切り分けたほうがいい
  private def setup(): Unit = {
    logger.info("start setup")
    val idMapGenerator = new JsonLatestDependencyIdListMapGenerator()
    val idMap = idMapGenerator.gen
    allDepsCalcurator.calcAllDep(idMap)
  }
}
