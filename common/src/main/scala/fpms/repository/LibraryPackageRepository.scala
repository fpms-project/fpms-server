package fpms.repository

import fpms.LibraryPackage
import fpms.LibraryPackageWithOutId

trait LibraryPackageRepository[F[_]] {

  def insert(pack: LibraryPackage): F[Unit]

  def insert(pack: LibraryPackageWithOutId): F[LibraryPackage]

  def insert(packs: List[LibraryPackage]): F[Unit]

  def findOne(name: String, version: String): F[Option[LibraryPackage]]

  def findOne(id: Int): F[Option[LibraryPackage]]

  def findByName(name: String): F[List[LibraryPackage]]

  def findByName(names: List[String]): F[List[LibraryPackage]]

  def findByIds(ids: List[Int]): F[List[LibraryPackage]]

  def findByDeps(depName: String): F[List[LibraryPackage]]

  def getNameList(limit: Int, offset: Int): F[List[String]]

  def getMaxId(): F[Int]

  def getNameCount(): F[Int]
}
