package fpms.repository

import io.circe.Json
import cats.data.NonEmptyList
import fs2.Stream
import fpms.SourcePackage
import fpms.SourcePackageInfo

trait SourcePackageRepository[F[_]] {

  def insert(name: String, version: String, deps: Json, deps_latest: Json): F[Int]

  def findByName(name: String): F[List[SourcePackage]]

  def find(name: String, version: String): F[Option[SourcePackage]]

  def findById(id: Int): F[Option[SourcePackage]]
  
  def findByIds(ids:  NonEmptyList[Int]): F[List[SourcePackage]]

  def findByDeps(depName: String): F[List[SourcePackage]]

  def updateLatest(name: String, version: String, depsLatest: Json): F[Unit]

  def updateLatest(id: Int, depsLatest: Json): F[Unit]

  def insertMulti(packs: List[SourcePackageInfo]): F[Unit]
}
