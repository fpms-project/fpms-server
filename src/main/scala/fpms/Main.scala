package fpms

import cats.effect.concurrent.MVar
import cats.effect.concurrent.Semaphore
import fpms.util.JsonLoader
import org.slf4j.LoggerFactory
import scala.util.control.Breaks
import scala.util.Try
import com.github.sh4869.semver_parser.{Range, SemVer}
import cats.effect.{IO, IOApp, ExitCode}
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.HttpRoutes
import cats.implicits._
import com.typesafe.config._
import doobie._

object Fpms extends IOApp {
  private val logger = LoggerFactory.getLogger(this.getClass)

  def helloWorldService(map: Map[Int, PackageNode]) = {
    import io.circe.generic.auto._
    import io.circe.syntax._
    import org.http4s.circe._
    import org.http4s.dsl.io._
    import org.http4s.implicits._
    import org.http4s.server.blaze._
    implicit val userDecoder = jsonEncoderOf[IO, Option[PackageNode]]
    HttpRoutes
      .of[IO] {
        case GET -> Root / "hello" / name =>
          Ok(s"Hello, $name.")
        case GET -> Root / "id" / IntVar(id) =>
          Ok(map.get(id))
      }
      .orNotFound
  }

  def saveToDb() {
    val config = ConfigFactory.load("app.conf").getConfig("server.postgresql")
    val xa = Transactor.fromDriverManager[IO](
      config.getString("driver"),
      config.getString("url"),
      config.getString("user"),
      config.getString("pass")
    )
    val packs = JsonLoader.loadIdList()
    util.SqlSaver.saveJson(packs, xa)
  }

  def run(args: List[String]): IO[ExitCode] = {
    if (args.headOption.exists(_ == "db")) {
      saveToDb()
    }
    logger.info("setup")
    val map = setup()
    algo(map)
    BlazeServerBuilder[IO]
      .bindHttp(8080, "localhost")
      .withHttpApp(helloWorldService(map))
      .serve
      .compile
      .drain
      .as(ExitCode.Success)
  }

  def setup(): Map[Int, PackageNode] = {
    val packs = JsonLoader.loadIdList()
    val packs_map = scala.collection.mutable.Map.empty[String, Seq[SourcePackageInfo]]
    // packs_map.sizeHint(packs.size)
    for (i <- 0 to packs.size - 1) {
      if (i % 100000 == 0) logger.info(s"convert to List: $i")
      val pack = packs(i)
      val seq = scala.collection.mutable.ArrayBuffer.empty[SourcePackageInfo]
      for (j <- 0 to pack.versions.size - 1) {
        val d = pack.versions(j)
        try {
          val info = SourcePackageInfo(pack.name, d.version, d.dep, d.id)
          seq += info
        } catch {
          case _: Throwable => Unit
        }
      }
      packs_map += (pack.name -> seq.toSeq)
    }
    logger.info("complete convert to list")
    var depCache = scala.collection.mutable.Map.empty[(String, String), Int]
    val packs_map_array = packs_map.values.toArray
    val map = scala.collection.mutable.Map.empty[Int, PackageNode]
    var hit = 0
    var miss = 0
    logger.info(s"pack_array_length : ${packs_map_array.size}")
    for (i <- 0 to packs_map_array.length - 1) {
      if (i % 100000 == 0) logger.info(s"count: ${i}, length: ${map.size} | hit : ${hit}, miss : ${miss}")
      val a = packs_map_array(i)
      for (j <- 0 to a.length - 1) {
        val pack = a(j)
        val id = pack.id
        try {
          if (pack.deps.isEmpty) {
            map.update(id, PackageNode(id, Seq.empty, scala.collection.mutable.Set.empty))
          } else {
            val depsx = scala.collection.mutable.ArrayBuffer.empty[Int]
            depsx.sizeHint(pack.deps.size)
            var failed = false
            var k = pack.deps.size - 1
            val seq = pack.deps.toSeq
            while (!failed && k > -1) {
              val d = seq(k)
              val cache = depCache.get(d)
              if (cache.isEmpty) {
                miss += 1
                var depP = for {
                  ds <- packs_map.get(d._1)
                  depP <- latestP(ds, d._2)
                } yield depP
                depP match {
                  case Some(v) => {
                    depsx += v.id
                    depCache.update(d, v.id)
                  }
                  case None => failed = true
                }
              } else {
                hit += 1
                depsx += cache.get
              }
              k -= 1
            }
            if (!failed) {
              map.update(id, PackageNode(id, depsx.toArray.toSeq, scala.collection.mutable.Set.empty))
            }
          }
        } catch {
          case e: Throwable => {
            logger.error(s"${e.getStackTrace().mkString("\n")}")
          }
        }
      }
    }
    logger.info("setup complete!")
    println("setup complete")
    map.toMap
  }

  def algo(map: Map[Int, PackageNode]) {
    System.gc()
    logger.info(s"get count : ${map.size}")
    logger.info("start loop")
    var count = 0
    val maps = map.values.toArray
    var set = scala.collection.mutable.TreeSet.empty[Int]
    var complete = false
    var first = true
    while (!complete) {
      logger.info(s"start   lap ${count}")
      val check = set.toSet
      set.clear()
      complete = true
      var x = 0
      for (i <- 0 to maps.size - 1) {
        if (i % 1000000 == 0) logger.info(s"count $count , $i")
        val node = maps(i)
        val deps = node.directed
        // 依存関係がない or 前回から変わっていない場合は無視
        if (deps.size == 0) {
          x += 1
        } else {
          var current = node.packages.size
          node.packages ++= node.directed.toSet
          for (j <- 0 to deps.size - 1) {
            val d = deps(j)
            // 更新されたやつだけ追加
            if (first || check.contains(d)) {
              val tar = map.get(d)
              if (tar.isDefined) {
                node.packages ++= tar.get.packages
              }
            }
          }
          if (node.packages.size != current) {
            complete = false
            set += node.src
          } else {
            x += 1
          }
        }
      }
      logger.info(s"complete lap ${count}, not updated: ${x}")
      count += 1
      first = false
    }
    logger.info("complete!")
  }

  def latestP(vers: Seq[SourcePackageInfo], condition: String): Option[SourcePackageInfo] = {
    Try {
      val range = Range(condition)
      for (i <- vers.length - 1 to 0 by -1) {
        if (range.valid(vers(i).version)) {
          return Some(vers(i))
        }
      }
      None
    }.getOrElse(None)
  }

  case class PackageNode(
      src: Int,
      directed: Seq[Int],
      packages: scala.collection.mutable.Set[Int]
  )
}
