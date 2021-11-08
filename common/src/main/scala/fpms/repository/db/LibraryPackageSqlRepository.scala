package fpms.repository.db

import cats.implicits.*
import io.circe.Json
import doobie.*
import doobie.implicits.*
import doobie.postgres.circe.json.implicits.*

import fpms.LibraryPackage
import fpms.repository.LibraryPackageRepository
import cats.effect.kernel.Async

class LibraryPackageSqlRepository[F[_]](conf: PostgresConfig)(implicit
    F: Async[F]
) extends LibraryPackageRepository[F] {

  lazy private val transactor = Transactor.fromDriverManager[F](
    "org.postgresql.Driver",
    conf.url,
    conf.username,
    conf.password
  )

  def insert(pack: LibraryPackage): F[Unit] = {
    val s = "insert into package (name, version, deps, id, shasum, integrity) values (?, ?, ?, ?, ?, ?)"
    Update[PackageSqlFormat](s).toUpdate0(PackageSqlFormat.from(pack)).run.transact(transactor).as(())
  }

  def insert(packs: List[LibraryPackage]): F[Unit] = {
    val s =
      "insert into package (name, version, deps, id, shasum, integrity) values (?, ?, ?, ?, ?, ?) ON CONFLICT ON CONSTRAINT package_name_version_key DO UPDATE SET id=?"
    Update[(String, String, Json, Int, String, Option[String], Int)](s)
      .updateMany[List](
        packs.map(PackageSqlFormat.from).map(v => (v.name, v.version, v.deps, v.id, v.shasum, v.integrity, v.id))
      )
      .transact(transactor)
      .as(())
  }

  def findOne(name: String, version: String): F[Option[LibraryPackage]] =
    sql"select name, version, deps, id, shasum, integrity from package where name = $name AND version = $version"
      .query[PackageSqlFormat]
      .option
      .map(x => x.map(_.to))
      .transact(transactor)

  def findOne(id: Int): F[Option[LibraryPackage]] =
    sql"select name, version, deps, id, shasum, integrity from package where id = $id"
      .query[PackageSqlFormat]
      .option
      .map(x => x.map(_.to))
      .transact(transactor)

  def findByDeps(depName: String): F[List[LibraryPackage]] =
    sql"select name, version, deps, id, shasum, integrity from package where json_extract_path(deps, ${depName}) is NOT NULL"
      .query[PackageSqlFormat]
      .to[List]
      .map(x => x.map(_.to))
      .transact(transactor)

  def findByName(name: String): F[List[LibraryPackage]] =
    sql"select name, version, deps, id, shasum, integrity from package where name = $name"
      .query[PackageSqlFormat]
      .to[List]
      .map(x => x.map(_.to))
      .transact(transactor)

  def findByIds(ids: List[Int]): F[List[LibraryPackage]] =
    ids match {
      case head :: tl => {
        val q = sql"select name, version, deps, id, shasum, integrity from package where " ++ Fragments
          .in(fr"id", (head :: tl).toNel.get)
        q.query[PackageSqlFormat].to[List].map(x => x.map(_.to)).transact(transactor)
      }
      case Nil => F.pure(List.empty[LibraryPackage])
    }

  def getMaxId(): F[Int] = {
    val q = sql"select MAX(id) from package"
    q.query[Max].unique.map(_.max).transact(transactor)
  }
}

case class Max(max: Int)
