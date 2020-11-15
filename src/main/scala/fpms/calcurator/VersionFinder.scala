package fpms.calcurator

import scala.util.Try

import com.github.sh4869.semver_parser.Range

import fpms.Package

object VersionFinder {
  implicit class Versions(seq: Seq[Package]) {
    def latestInFits(condition: String): Option[Package] = {
      Try {
        val range = Range(condition)
        var x: Option[Package] = None
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
