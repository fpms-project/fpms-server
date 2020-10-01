package fpms.repository

import io.circe.Json
import cats.data.NonEmptyList
import fs2.Stream
import fpms.SourcePackage

trait SourcePackageRepository[F[_]] {

  def insert(name: String, version: String, deps: Json): F[Int]

  def insert(pack: SourcePackage): F[Unit]

  def insertMulti(packs: List[SourcePackage]): F[Unit]

  def find(name: String, version: String): F[Option[SourcePackage]]
  
  def findByName(name: String): F[List[SourcePackage]]

  def findById(id: Int): F[Option[SourcePackage]]

  def findByIds(ids: NonEmptyList[Int]): F[List[SourcePackage]]

  def findByDeps(depName: String): F[List[SourcePackage]]

  def getMaxId(): F[Int]
}
