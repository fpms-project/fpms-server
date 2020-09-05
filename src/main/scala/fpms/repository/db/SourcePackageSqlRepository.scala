package fpms.repository.db

import fpms.SourcePackageRepository
import doobie._
import doobie.implicits._
import doobie.postgres.circe.json.implicits._
import cats._
import io.circe._
import cats.implicits._
import cats.effect.implicits._
import cats.effect.Bracket
import cats.effect.ConcurrentEffect
import cats.data.NonEmptyList
import fpms.SourcePackage
import fpms.SourcePackageInfo
import fs2.Stream

class SourcePackageSqlRepository[F[_]](transactor: Transactor[F])(implicit
    ev: Bracket[F, Throwable],
    F: ConcurrentEffect[F]
) extends SourcePackageRepository[F] {

  def insert(name: String, version: String, deps: Json, deps_latest: Json): F[Int] =
    sql"insert into package (name, version, deps, deps_latest) values ($name, $version, $deps, $deps_latest)".update
      .withUniqueGeneratedKeys[Int]("id")
      .transact(transactor)

  def insertMulti(packs: List[SourcePackageInfo]): F[List[SourcePackage]] = {
    val s = "insert into package (name, version, deps, deps_latest) values (?, ?, ?, \'{}\')"
    Update[SourcePackageInfo](s)
      .updateManyWithGeneratedKeys[SourcePackage]("id", "name", "version", "deps", "deps_latest")(packs)
      .transact(transactor)
      .compile
      .toList
  }

  def insertMultiStream(packs: List[SourcePackageInfo]): Stream[F, SourcePackage] = {
    val s = "insert into package (name, version, deps, deps_latest) values (?, ?, ?, \'{}\')"
    Update[SourcePackageInfo](s)
      .updateManyWithGeneratedKeys[SourcePackage]("id", "name", "version", "deps", "deps_latest")(packs)
      .transact(transactor)
  }

  def updateLatest(name: String, version: String, depsLatest: Json): F[Unit] =
    sql"update package set latest_deps = $depsLatest where name = $name AND version = $version".update.run
      .transact(transactor)
      .as(())

  def updateLatest(id: Int, depsLatest: Json): F[Unit] =
    sql"update package set latest_deps = $depsLatest where id = $id".update.run.transact(transactor).as(())

  def find(name: String, version: String): F[Option[SourcePackage]] =
    sql"select id, name, version, desp, latest_deps from package where name = $name AND version = $version"
      .query[SourcePackage]
      .option
      .transact(transactor)

  def findByDeps(depName: String): F[List[SourcePackage]] =
    sql"select id, name, version, deps, latest_deps from package where json_extract_path(deps, ${depName}) is NOT NULL"
      .query[SourcePackage]
      .to[List]
      .transact(transactor)

  def findByName(name: String): F[List[SourcePackage]] =
    sql"select id, name, version, deps, latest_deps from package where name = $name"
      .query[SourcePackage]
      .to[List]
      .transact(transactor)

  def findById(id: Int): F[Option[SourcePackage]] =
    sql"select id, name, version, deps, latest_deps from package where id = $id"
      .query[SourcePackage]
      .option
      .transact(transactor)

  def findByIds(ids: NonEmptyList[Int]): F[List[SourcePackage]] = {
    val q = sql"select id, name, version, deps, latest_deps from package where" ++ Fragments.in(fr"id", ids)
    q.query[SourcePackage].to[List].transact(transactor)
  }
}
