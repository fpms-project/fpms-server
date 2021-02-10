package fpms.server.util

import scala.util.Try

import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging

import fpms.LibraryPackage
import fpms.server.json.RootInterfaceN
import fpms.server.repository.db.LibraryPackageSqlRepository

object SqlSaver extends LazyLogging {
  val GROUPED = 100
  def saveJson(packages: List[RootInterfaceN], repo: LibraryPackageSqlRepository[IO]) = {
    packages.grouped(GROUPED).zipWithIndex.foreach {
      case (v, i) => {
        if ((i * GROUPED) % 10000 == 0) logger.info(s"save in db: ${i * GROUPED}/${packages.length}")
        val list = v
          .map(pack =>
            pack.versions
              .map(x => Try { Some(LibraryPackage(pack.name, x.version, x.dep, x.id)) }.getOrElse(None))
              .flatten
          )
          .flatten
        repo.insert(list.toList).unsafeRunSync()
      }
    }
  }
}
