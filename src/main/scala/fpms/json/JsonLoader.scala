package fpms.json

import scala.io.Source

import com.typesafe.config.ConfigFactory
import io.circe.generic.auto._
import io.circe.parser.decode
import io.circe.syntax._
import java.io.PrintWriter
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import org.slf4j.LoggerFactory

import fpms.LibraryPackage

object JsonLoader {

  private lazy val logger = LoggerFactory.getLogger(this.getClass)
  private lazy val config = ConfigFactory.load("app.conf").getConfig("json")
  lazy val MAX_FILE_COUNT = config.getInt("filenum")

  def loadList(start: Int = 0, end: Int = MAX_FILE_COUNT): Array[RootInterface] = {
    var lists = Seq.empty[Option[List[RootInterface]]]
    for (i <- start to end) {
      logger.info(s"json file: ${i}/${end}")
      val src = readFile(filepath(i))
      val dec = decode[List[RootInterface]](src) match {
        case Right(v) => Some(v)
        case Left(_)  => None
      }
      lists = lists :+ dec.map(x => x.map(v => v.copy(name = URLDecoder.decode(v.name, StandardCharsets.UTF_8.name))))
    }
    lists.flatten.flatten[RootInterface].toArray
  }

  def loadIdList(start: Int = 0, end: Int = MAX_FILE_COUNT): Array[RootInterfaceN] = {
    var lists = Seq.empty[Option[List[RootInterfaceN]]]
    for (i <- start to end) {
      logger.info(s"json file: ${i}/${end}")
      val src = readFile(s"${config.getString("idjsondir")}$i.json")
      val dec = decode[List[RootInterfaceN]](src) match {
        case Right(v) => Some(v)
        case Left(_)  => None
      }
      lists = lists :+ dec.map(x => x.map(v => v.copy(name = URLDecoder.decode(v.name, StandardCharsets.UTF_8.name))))
    }
    lists.flatten.flatten[RootInterfaceN].toArray
  }

  def convertJson(start: Int = 0, end: Int = MAX_FILE_COUNT) {
    logger.info("convert to json")
    var id = 0;
    for (i <- start to end) {
      val src = readFile(filepath(i))
      decode[List[RootInterface]](src) match {
        case Right(v) => {
          val x = convertList(v, id)
          id = x._2
          new PrintWriter(s"jsons/id/${i}.json") {
            write(x._1.asJson.toString())
            close()
          }
        }
        case Left(_) => None
      }
    }
  }

  def createMap(): Map[String, Seq[LibraryPackage]] = {
    val packs = loadIdList()
    val packs_map = scala.collection.mutable.Map.empty[String, Seq[LibraryPackage]]
    for (i <- 0 to packs.size - 1) {
      if (i % 100000 == 0) logger.info(s"convert to List: $i")
      val pack = packs(i)
      val seq = scala.collection.mutable.ArrayBuffer.empty[LibraryPackage]
      for (j <- 0 to pack.versions.size - 1) {
        val d = pack.versions(j)
        try {
          val info = LibraryPackage(pack.name, d.version, d.dep, d.id)
          seq += info
        } catch {
          case _: Throwable => Unit
        }
      }
      packs_map += (pack.name -> seq.toSeq)
    }
    packs_map.toMap
  }

  private def convertList(array: List[RootInterface], start: Int): (List[RootInterfaceN], Int) = {
    var id = start
    val result = array.map(y =>
      RootInterfaceN(y.name, y.versions.map(v => {
        val x = NpmPackageWithId(v.version, v.dep, id)
        id += 1
        x
      }))
    )
    (result, id)
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
