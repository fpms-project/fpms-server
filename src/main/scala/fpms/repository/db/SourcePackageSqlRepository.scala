package fpms.repository.db

import fpms.repository.SourcePackageRepository
import doobie._
import doobie.implicits._
import doobie.postgres.circe.json.implicits._
import doobie.Write
import cats._
import io.circe._
import io.circe.syntax._
import io.circe.generic.auto._
import cats.implicits._
import cats.effect.implicits._
import cats.effect.Bracket
import cats.effect.ConcurrentEffect
import cats.data.NonEmptyList
import fpms.Package
import fs2.Stream
import org.slf4j.LoggerFactory

class SourcePackageSqlRepository[F[_]: ConcurrentEffect](transactor: Transactor[F]) extends SourcePackageRepository[F] {
  private val logger = LoggerFactory.getLogger(this.getClass)

  def insert(pack: Package): F[Unit] = {
    val s = "insert into package (name, version, deps, id) values (?, ?, ?, ?)"
    Update[PackageSqlFormat](s).toUpdate0(PackageSqlFormat.from(pack)).run.transact(transactor).as(Unit)
  }

  def insert(packs: List[Package]): F[Unit] = {
    val s = "insert into package (name, version, deps, id) values (?, ?, ?, ?)"
    Update[PackageSqlFormat](s).updateMany(packs.map(PackageSqlFormat.from)).transact(transactor).as(Unit)
  }

  def findOne(name: String, version: String): F[Option[Package]] =
    sql"select name, version, deps, id from package where name = $name AND version = $version"
      .query[PackageSqlFormat]
      .option
      .map(x => x.map(_.to))
      .transact(transactor)

  def findOne(id: Int): F[Option[Package]] =
    sql"select name, version, deps, id from package where id = $id"
      .query[PackageSqlFormat]
      .option
      .map(x => x.map(_.to))
      .transact(transactor)

  def findByDeps(depName: String): F[List[Package]] =
    sql"select name, version, deps, id from package where json_extract_path(deps, ${depName}) is NOT NULL"
      .query[PackageSqlFormat]
      .to[List]
      .map(x => x.map(_.to))
      .transact(transactor)

  def findByName(name: String): F[List[Package]] =
    sql"select name, version, deps, id from package where name = $name"
      .query[PackageSqlFormat]
      .to[List]
      .map(x => x.map(_.to))
      .transact(transactor)

  def findByIds(ids: NonEmptyList[Int]): F[List[Package]] = {
    val q = sql"select name, version, deps, id from package where " ++ Fragments.in(fr"id", ids)
    q.query[PackageSqlFormat].to[List].map(x => x.map(_.to)).transact(transactor)
  }

  def getMaxId(): F[Int] = {
    val q = sql"select MAX(id) from package"
    q.query[Max].unique.map(_.max).transact(transactor)
  }
}
case class Max(max: Int)
