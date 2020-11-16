package fpms.repository.db

import cats.data.NonEmptyList
import cats.effect.ConcurrentEffect
import cats.implicits._
import doobie._
import doobie.implicits._
import doobie.postgres.circe.json.implicits._

import fpms.LibraryPackage
import fpms.repository.SourcePackageRepository

class SourcePackageSqlRepository[F[_]: ConcurrentEffect](transactor: Transactor[F]) extends SourcePackageRepository[F] {

  def insert(pack: LibraryPackage): F[Unit] = {
    val s = "insert into package (name, version, deps, id) values (?, ?, ?, ?)"
    Update[PackageSqlFormat](s).toUpdate0(PackageSqlFormat.from(pack)).run.transact(transactor).as(Unit)
  }

  def insert(packs: List[LibraryPackage]): F[Unit] = {
    val s = "insert into package (name, version, deps, id) values (?, ?, ?, ?)"
    Update[PackageSqlFormat](s).updateMany(packs.map(PackageSqlFormat.from)).transact(transactor).as(Unit)
  }

  def findOne(name: String, version: String): F[Option[LibraryPackage]] =
    sql"select name, version, deps, id from package where name = $name AND version = $version"
      .query[PackageSqlFormat]
      .option
      .map(x => x.map(_.to))
      .transact(transactor)

  def findOne(id: Int): F[Option[LibraryPackage]] =
    sql"select name, version, deps, id from package where id = $id"
      .query[PackageSqlFormat]
      .option
      .map(x => x.map(_.to))
      .transact(transactor)

  def findByDeps(depName: String): F[List[LibraryPackage]] =
    sql"select name, version, deps, id from package where json_extract_path(deps, ${depName}) is NOT NULL"
      .query[PackageSqlFormat]
      .to[List]
      .map(x => x.map(_.to))
      .transact(transactor)

  def findByName(name: String): F[List[LibraryPackage]] =
    sql"select name, version, deps, id from package where name = $name"
      .query[PackageSqlFormat]
      .to[List]
      .map(x => x.map(_.to))
      .transact(transactor)

  def findByIds(ids: NonEmptyList[Int]): F[List[LibraryPackage]] = {
    val q = sql"select name, version, deps, id from package where " ++ Fragments.in(fr"id", ids)
    q.query[PackageSqlFormat].to[List].map(x => x.map(_.to)).transact(transactor)
  }

  def getMaxId(): F[Int] = {
    val q = sql"select MAX(id) from package"
    q.query[Max].unique.map(_.max).transact(transactor)
  }
}

case class Max(max: Int)
