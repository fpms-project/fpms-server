package fpms.calcurator

import com.typesafe.scalalogging.LazyLogging

class LocalDependencyCalculator extends DependencyCalculator with LazyLogging {
  private var map = Map.empty[Int, PackageCalcuratedDeps]

  def initialize(): Unit = {
    setup()
  }

  def getAll = map.toMap

  def get(id: Int): Option[PackageCalcuratedDeps] = map.get(id)

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
    map = new AllDepsCalcurator().calcAllDep(idMap)
  }
}
