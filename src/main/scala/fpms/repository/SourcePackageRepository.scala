package fpms.repository

import cats.data.NonEmptyList
import fpms.LibraryPackage

trait SourcePackageRepository[F[_]] {

  def insert(pack: LibraryPackage): F[Unit]

  def insert(packs: List[LibraryPackage]): F[Unit]

  def findOne(name: String, version: String): F[Option[LibraryPackage]]
  
  def findOne(id: Int): F[Option[LibraryPackage]]
  
  def findByName(name: String): F[List[LibraryPackage]]

  def findByIds(ids: NonEmptyList[Int]): F[List[LibraryPackage]]

  def findByDeps(depName: String): F[List[LibraryPackage]]

  def getMaxId(): F[Int]
}
