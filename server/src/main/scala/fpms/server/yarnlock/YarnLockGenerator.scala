package fpms.server.yarnlock

import fpms.LibraryPackage
import com.github.sh4869.semver_parser.Range

object YarnLockGenerator {
  val HEAD = """# THIS IS GENERATE BY fpms
# yarn lockfile v1



"""

  def generateYarn(packs: Set[LibraryPackage], request: (String, String)) = {
    val ap =
      scala.collection.mutable.Map.from(packs.map(v => (v, Seq.empty[(String, String)])).toMap)
    getSatisfyPackage(request._1, request._2, packs).collect { l =>
      ap.update(l, ap.get(l).getOrElse(Seq()) :+ request)
    }
    packs.foreach(v => {
      v.deps.map(dep =>
        getSatisfyPackage(dep._1, dep._2, packs).collect { l => ap.update(l, ap.get(l).getOrElse(Seq()) :+ dep) }
      )
    })
    HEAD + ap.toSeq.sortWith { case (l, r) => l._1.name < r._1.name }
      .map(z => YarnLockComponent(z._2, z._1).toStr)
      .mkString("\n") + "\n"
  }

  private def getSatisfyPackage(name: String, range: String, packs: Set[LibraryPackage]) = {
    val r = Range(range)
    packs
      .filter(v => (v.name == name) && r.valid(v.version))
      .toList
      .sortWith { case (l, r) => l.version > r.version }
      .headOption
  }
}
