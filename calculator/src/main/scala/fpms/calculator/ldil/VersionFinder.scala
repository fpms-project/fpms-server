package fpms.calculator.ldil

import com.github.sh4869.semver_parser.Range

import fpms.LibraryPackage
import com.typesafe.scalalogging.LazyLogging

object VersionFinder extends LazyLogging {
  implicit class Versions(seq: Seq[LibraryPackage]) {
    def latestInFits(condition: String): Either[VersionFindError, LibraryPackage] = {
      try {
        val range = Range(condition)
        var x: Option[LibraryPackage] = None
        seq.foreach { v => if (range.valid(v.version) && x.forall(z => v.version > z.version)) x = Some(v) }
        if (x.isEmpty) {
          Left(NotFound)
        } else {
          Right(x.get)
        }
      } catch {
        case _: Throwable => Left(ParseRangeError)
      }
    }
  }
  sealed trait VersionFindError
  case object NotFound extends VersionFindError
  case object ParseRangeError extends VersionFindError
}
