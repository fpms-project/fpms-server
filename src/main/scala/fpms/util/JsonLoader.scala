package fpms.util

import fpms.RootInterface
import io.circe.generic.auto._
import io.circe.parser.decode
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.net.URLEncoder
import scala.io.Source
import fpms.PackageInfo
import java.net.URLDecoder
import com.typesafe.config.ConfigFactory

object JsonLoader {

  private lazy val logger = LoggerFactory.getLogger(this.getClass)
  private lazy val config = ConfigFactory.load("app.conf").getConfig("json")
  lazy val MAX_FILE_COUNT = config.getInt("filenum")
  
  def createLists(count: Int = MAX_FILE_COUNT): Array[RootInterface] = {
    var lists = Seq.empty[Option[List[RootInterface]]]
    for (i <- 0 to count) {
      val src = readFile(filepath(i))
      val dec = decode[List[RootInterface]](src) match {
        case Right(v) => Some(v)
        case Left(e) => None
      }
      lists = lists :+ dec.map(x => x.map(v => v.copy(name = URLDecoder.decode(v.name, StandardCharsets.UTF_8.name))))
    }
    lists.flatten.flatten[RootInterface].toArray
  }

  private def filepath(count: Int): String =
    s"${config.getString("jsondir")}$count.json"

  private def readFile(filename: String): String = {
    val source = Source.fromFile(filename)
    val result = source.getLines.mkString
    source.close()
    result
  }
}
