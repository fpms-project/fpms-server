package fpms.calculator.package_map

import cats.effect.kernel.Async
import com.typesafe.scalalogging.LazyLogging
import fpms.LibraryPackage
import fpms.calculator.json.JsonLoader

class JsonPackageMapGenerator[F[_]: Async] extends PackageMapGenerator[F] with LazyLogging {
  override def getMap = {
    val packs = JsonLoader.loadIdList()
    logger.info("loaded json files")
    val packsMap = scala.collection.mutable.Map.empty[String, Seq[LibraryPackage]]
    packs.foreach { pack =>
      val seq = scala.collection.mutable.ListBuffer.empty[LibraryPackage]
      pack.versions.foreach { d =>
        try {
          val info = LibraryPackage(pack.name, d.version, d.dep, d.id, d.shasum, d.integrity)
          seq += info
        } catch {
          case _: Throwable => {
            logger.info(s"error parsing version: ${pack.name}, ${d.version}")
          }
        }
      }
      packsMap += (pack.name -> seq.toSeq)
    }
    logger.info("complete convert to list")
    Async[F].pure(packsMap.toMap)
  }
}
