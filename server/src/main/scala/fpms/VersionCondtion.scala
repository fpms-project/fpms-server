package fpms

import com.gilt.gfc.semver.SemVer
import scala.util.Try

object VersionCondition {

  implicit class VersionCondition(val src: String) {
    def valid(ver: SemVer): Boolean = Try{semver.SemVer.satisfies(ver.original, src)}.getOrElse(false)
  }

}

