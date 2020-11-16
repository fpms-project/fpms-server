package fpms.calcurator

import scala.util.Try

import com.github.sh4869.semver_parser.Range

import fpms.LibraryPackage

object VersionFinder {
  implicit class Versions(seq: Seq[LibraryPackage]) {
    def latestInFits(condition: String): Option[LibraryPackage] = {
      Try {
        val range = Range(condition)
        var x: Option[LibraryPackage] = None
        for (i <- 0 to seq.length - 1) {
          if (range.valid(seq(i).version) && x.forall(v => seq(i).version > v.version)) {
            x = Some(seq(i))
          }
        }
        x
      }.getOrElse(None)
    }
  }
}
