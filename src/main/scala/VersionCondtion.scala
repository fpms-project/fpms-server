package package_manager_server

import com.gilt.gfc.semver.SemVer

object VersionCondition {

  implicit class VersionCondition(val src: String) {
    def valid(ver: SemVer): Boolean = semver.SemVer.satisfies(ver.original, src)
  }

}

