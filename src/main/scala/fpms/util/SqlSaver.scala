package fpms.util

import scala.util.Try

import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging

import fpms._
import fpms.json.RootInterfaceN
import fpms.repository.db.LibraryPackageSqlRepository

object SqlSaver extends LazyLogging {
  def saveJson(packages: Array[RootInterfaceN], repo: LibraryPackageSqlRepository[IO]) = {
    for (i <- 0 to packages.length - 1) {
      if (i % 10000 == 0) logger.info(s"save in db: ${i + 1}/${packages.length - 1}")
      val pack = packages(i)
      val list =
        pack.versions.map(x => Try { Some(LibraryPackage(pack.name, x.version, x.dep, x.id)) }.getOrElse(None)).flatten
      repo.insert(list.toList).unsafeRunSync()
    }
  }
}
