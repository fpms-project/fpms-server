package fpms.util

import fpms.RootInterface
import io.circe.generic.auto._
import io.circe.parser.decode

import scala.io.Source

object JsonLoader {

  val MAX_FILE_COUNT = 31

  def createLists(count: Int = MAX_FILE_COUNT): Seq[RootInterface] = {
    var lists = Seq.empty[Option[List[RootInterface]]]
    for (i <- 0 to count) {
      lists = lists :+ parse(readFile(filepath(i)))
    }
    lists.flatten.flatten[RootInterface]
  }

  private def filepath(count: Int): String =
    s"/home/sh4869/Projects/package-manager-server/jsons/$count.json"

  private def readFile(filename: String): String = {
    val source = Source.fromFile(filename)
    val result = source.getLines.mkString
    source.close()
    result
  }

  private def parse(src: String): Option[List[RootInterface]] =
    decode[List[RootInterface]](src).toOption
}
