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
        seq.foreach { v =>
          if (range.valid(v.version) && x.forall(z => v.version > z.version)) {
            x = Some(v)
          }
        }
        x
      }.getOrElse(None)
    }
  }
}
