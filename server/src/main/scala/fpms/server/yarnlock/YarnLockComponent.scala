package fpms.server.yarnlock

import fpms.LibraryPackage

case class YarnLockComponent(from: Seq[(String, String)], pack: LibraryPackage) {
  def toStr: String = {
    val inte = pack.integrity.map(v => s"\n  integrity ${v}").getOrElse("")
    val deps =
      if (pack.deps.isEmpty) ""
      else {
        "\n  dependencies:\n" + pack.deps.map { case (name, v) => s"""    $name "$v"""" }.mkString("\n")
      }
    s"""
${from.map((a) => s""""${a._1}@${a._2}"""").mkString(", ")}:
  version "${pack.version.original}"
  resolved "https://registry.yarnpkg.com/${pack.name}/-/${pack.name}-${pack.version.original}.tgz#${pack.shasum}"""" + inte + deps
  }
}
