package fpms.calculator.util

import scala.util.Try

import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging

import fpms.LibraryPackage
import fpms.calculator.json.RootInterfaceN
import fpms.repository.db.LibraryPackageSqlRepository

object PackageSaver extends LazyLogging {
  val GROUPED = 100
  def saveJson(packages: List[RootInterfaceN], repo: LibraryPackageSqlRepository[IO]) = {
    packages.grouped(GROUPED).zipWithIndex.foreach {
      case (v, i) => {
        if ((i * GROUPED) % 10000 == 0) logger.info(s"save in db: ${i * GROUPED}/${packages.length}")
        val list = v
          .map(pack =>
            pack.versions
              .map(x =>
                Try { Some(LibraryPackage(pack.name, x.version, x.dep, x.id, x.shasum, x.integrity)) }.getOrElse(None)
              )
              .flatten
          )
          .flatten
        repo.insert(list.toList).unsafeRunSync()
      }
    }
  }
}
