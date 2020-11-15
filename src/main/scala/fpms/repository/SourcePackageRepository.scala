package fpms.repository

import io.circe.Json
import cats.data.NonEmptyList
import fs2.Stream
import fpms.Package

trait SourcePackageRepository[F[_]] {

  def insert(pack: Package): F[Unit]

  def insert(packs: List[Package]): F[Unit]

  def findOne(name: String, version: String): F[Option[Package]]
  
  def findOne(id: Int): F[Option[Package]]
  
  def findByName(name: String): F[List[Package]]

  def findByIds(ids: NonEmptyList[Int]): F[List[Package]]

  def findByDeps(depName: String): F[List[Package]]

  def getMaxId(): F[Int]
}
