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
import fpms.SourcePackage
import fs2.Stream
import fpms.SourcePackageSave
import org.slf4j.LoggerFactory

class SourcePackageSqlRepository[F[_]](transactor: Transactor[F])(
    implicit
    ev: Bracket[F, Throwable],
    F: ConcurrentEffect[F]
) extends SourcePackageRepository[F] {
  private val logger = LoggerFactory.getLogger(this.getClass)

  def insert(name: String, version: String, deps: Json): F[Int] =
    sql"insert into package (name, version, deps) values ($name, $version, $deps)".update
      .withUniqueGeneratedKeys[Int]("id")
      .transact(transactor)

  def insert(pack: SourcePackage): F[Unit] = {
    val s = "insert into package (name, version, deps, id) values (?, ?, ?, ?)"
    Update[SourcePackageSave](s).toUpdate0(pack.to).run.transact(transactor).as(Unit)
  }

  def insertMulti(packs: List[SourcePackage]): F[Unit] = {
    val s = "insert into package (name, version, deps, id) values (?, ?, ?, ?)"
    Update[SourcePackageSave](s).updateMany(packs.map(_.to)).transact(transactor).as(Unit)
  }

  def find(name: String, version: String): F[Option[SourcePackage]] =
    sql"select name, version, deps, id from package where name = $name AND version = $version"
      .query[SourcePackageSave]
      .option
      .map(x => x.map(_.to))
      .transact(transactor)

  def findByDeps(depName: String): F[List[SourcePackage]] =
    sql"select name, version, deps, id from package where json_extract_path(deps, ${depName}) is NOT NULL"
      .query[SourcePackageSave]
      .to[List]
      .map(x => x.map(_.to))
      .transact(transactor)

  def findByName(name: String): F[List[SourcePackage]] =
    sql"select name, version, deps, id from package where name = $name"
      .query[SourcePackageSave]
      .to[List]
      .map(x => x.map(_.to))
      .transact(transactor)

  def findById(id: Int): F[Option[SourcePackage]] =
    sql"select name, version, deps, id from package where id = $id"
      .query[SourcePackageSave]
      .option
      .map(x => x.map(_.to))
      .transact(transactor)

  def findByIds(ids: NonEmptyList[Int]): F[List[SourcePackage]] = {
    val q = sql"select name, version, deps, id from package where " ++ Fragments.in(fr"id", ids)
    q.query[SourcePackageSave].to[List].map(x => x.map(_.to)).transact(transactor)
  }

  def getMaxId(): F[Int] = {
    val q = sql"select MAX(id) from package"
    q.query[Max].unique.map(_.max).transact(transactor)
  }
}
case class Max(max: Int)
