package fpms.util

import fpms._
import fpms.repository.db.SourcePackageSqlRepository
import doobie._
import doobie.implicits._
import doobie.postgres.circe.json.implicits._
import cats.effect.IO
import cats.effect.Bracket
import cats.effect.ConcurrentEffect
import org.slf4j.LoggerFactory
import scala.util.Try

object SqlSaver {
  private lazy val logger = LoggerFactory.getLogger(this.getClass)
  def saveJson(packages: Array[RootInterfaceN], transactor: Transactor[IO])(
      implicit
      ev: Bracket[IO, Throwable],
      F: ConcurrentEffect[IO]
  ) = {
    val repo = new SourcePackageSqlRepository[IO](transactor)
    for (i <- 0 to packages.length - 1) {
      if (i % 10000 == 0) logger.info(s"save in db: $i")
      val pack = packages(i)
      val list = pack.versions
        .map(x => Try { Some(SourcePackageInfo(pack.name, x.version, x.dep, x.id)) }.getOrElse(None))
        .flatten
      repo.insertMulti(list.toList).unsafeRunSync()
    }
  }
}
