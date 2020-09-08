package fpms

import fpms.util.JsonLoader
import org.slf4j.LoggerFactory
import scala.util.control.Breaks
import scala.util.Try
import com.github.sh4869.semver_parser.{Range, SemVer}
import fpms.repository.db.SourcePackageSqlRepository
import cats.effect.{IOApp, IO, ExitCode}
import cats.implicits._
import java.io.PrintWriter
import com.typesafe.config._

object Fpms extends IOApp {
  private lazy val logger = LoggerFactory.getLogger(this.getClass)

  def helloWorldService(map: Map[Int, PackageNode]) = {
    import io.circe.generic.auto._
    import io.circe.syntax._
    import org.http4s.circe._
    import org.http4s.HttpRoutes
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

  def run(args: List[String]): IO[ExitCode] = {
    if (args.headOption.exists(_ == "save")) {
      saveToJson();
      IO.unit.as(ExitCode.Success)
    } else if (args.get(0).exists(_ == "setup")) {
      logger.info("setup")
      val map = setup()
      algo(map)
      /*
      BlazeServerBuilder[IO]
        .bindHttp(8080, "localhost")
        .withHttpApp(helloWorldService(map))
        .serve
        .compile
        .drain
        .as(ExitCode.Success)
       */
      IO.unit.as(ExitCode.Success)
    } else {
      IO.unit.as(ExitCode.Success)
    }
  }

  def saveToJson() = {
    val max = 63
    /*
    for (i <- 61 to 63) {
      val start = i
      val end = i
      val packs = JsonLoader.loadList(start, end)
      val l = packs
        .map(pack =>
          pack.versions
            .map(x =>
              Try { SemVer.parse(x.version) }
                .getOrElse(None)
                .map(_ => {
                  SourcePackageInfo(
                    pack.name,
                    x.version,
                    x.dep.getOrElse(Map.empty[String, String]).map(x => (x._1, x._2.replace("'", ""))).asJson,
                    0
                  )
                })
            )
            .toList
            .flatten
        )
        .flatten
        .toList
      new PrintWriter(s"jsons/with_id_${i - 30}.json") {
        write(l.asJson.toString());
        close()
      }
    }
   */
  }

  def setup(): Map[Int, PackageNode] = {
    val packs = JsonLoader.loadList()
    val packs_map = scala.collection.mutable.Map.empty[String, Seq[SourcePackageInfo]]
    logger.info(s"pack size of types : ${packs.size}")
    var id = 0
    for (i <- 0 to packs.size - 1) {
      if (i % 100000 == 0) logger.info(s"convert to List[SourcePcakgeInfo] : ${i}")
      val pack = packs(i)
      val l = pack.versions
        .map(x => {
          id += 1
          Try {
            Some(SourcePackageInfo(pack.name, SemVer(x.version), x.dep.getOrElse(Map.empty[String, String]), id))
          }.getOrElse(None)
        })
        .toList
        .flatten
      packs_map += (pack.name -> l.toSeq)
    }
    logger.info("complete convert to list")
    var miss = 0
    var hit = 0
    var depCache = scala.collection.mutable.Map.empty[(String, String), Int]
    val packs_map_array = packs_map.values.toArray
    val map = scala.collection.mutable.Map.empty[Int, PackageNode]
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
                    depsx += pack.id
                    depCache.update(d, pack.id)
                  }
                  case None => failed = true
                }
              } else {
                hit += 1
                depsx += cache.get
              }
              k -= 1
            }
            if (!failed) map.update(id, PackageNode(id, depsx.toArray.toSeq, scala.collection.mutable.Set.empty))
          }
        } catch {
          case e: Throwable => logger.error(s"${e.getStackTrace().mkString("\n")}")
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
      System.gc()
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
