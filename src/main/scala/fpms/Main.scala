package fpms

import io.circe._, io.circe.generic.auto._, io.circe.parser._, io.circe.syntax._
import cats.effect.concurrent.Semaphore
import fpms.util.JsonLoader
import org.slf4j.LoggerFactory
import scala.util.control.Breaks
import scala.util.Try
import doobie._
import doobie.implicits._
import com.github.sh4869.semver_parser.{Range, SemVer}
import fpms.repository.db.SourcePackageSqlRepository
import cats.effect._
import cats.implicits._
import org.http4s.HttpRoutes
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.server.blaze._
import java.io.PrintWriter
import scala.concurrent.ExecutionContext.global
import com.typesafe.config._

object Fpms extends IOApp {
  private lazy val logger = LoggerFactory.getLogger(this.getClass)

  lazy val helloWorldService = HttpRoutes
    .of[IO] {
      case GET -> Root / "hello" / name =>
        Ok(s"Hello, $name.")
    }
    .orNotFound

  def run(args: List[String]): IO[ExitCode] = {
    val config = ConfigFactory.load("app.conf").getConfig("server.postgresql")
    val xa = Transactor.fromDriverManager[IO](
      config.getString("driver"),
      config.getString("url"),
      config.getString("user"),
      config.getString("pass")
    )
    val repo = new SourcePackageSqlRepository[IO](xa)
    if (args.get(0).exists(_ == "setup")) {
      logger.info("setup")
      save(repo)
      IO.unit.as(ExitCode.Success)
      /*
      val map = setup(repo)
      algo(map)
       */
    } else {
      val name = "a"
      println(repo.findByDeps("a").unsafeRunSync())
      BlazeServerBuilder[IO]
        .bindHttp(8080, "localhost")
        .withHttpApp(helloWorldService)
        .serve
        .compile
        .drain
        .as(ExitCode.Success)
    }
  }

  def save(repo: SourcePackageRepository[IO]) = {
    val packs = JsonLoader.createLists()
    val l = packs
      .map(pack =>
        pack.versions
          .map(x =>
            Try { SemVer.parse(x.version) }
              .getOrElse(None)
              .map(_ =>
                SourcePackageInfo(
                  pack.name,
                  x.version,
                  x.dep.getOrElse(Map.empty[String, String]).map(x => (x._1, x._2.replace("'", ""))).asJson
                )
              )
          )
          .toList
          .flatten
      )
      .flatten
      .toList
    new PrintWriter("test.sql") {
      write(s"insert into package (name, version, deps, deps_latest) values ${l
        .map(x => s"('${x.name}', '${x.version}', '${x.deps.toString()}', '{}')")
        .mkString(",")}"); close
    }
    // repo.insertMultiStream(l)
  }

  def setup(repo: SourcePackageRepository[IO]): Map[Int, PackageNode] = {
    val packs = JsonLoader.createLists()
    val packs_map = scala.collection.mutable.Map.empty[String, Seq[SourcePackage]]
    logger.info(s"pack size of types : ${packs.size}")
    for (i <- 0 to packs.size - 1) {
      if (i % 10000 == 0) logger.info(s"$i")
      val pack = packs(i)
      val l = pack.versions
        .map(x => SourcePackageInfo(pack.name, x.version, x.dep.getOrElse(Map.empty[String, String]).asJson))
        .toList
      val ok = repo.insertMulti(l).unsafeRunSync()
    }
    var depCache = scala.collection.mutable.Map.empty[(String, String), Int]
    val packs_map_array = packs_map.values.toArray
    val map = scala.collection.mutable.Map.empty[Int, PackageNode]
    logger.info(s"pack_array_length : ${packs_map_array.size}")
    for (i <- 0 to packs_map_array.length - 1) {
      if (i % 100000 == 0) logger.info(s"count: ${i}, length: ${map.size}")
      val a = packs_map_array(i)
      for (j <- 0 to a.length - 1) {
        val pack = a(j)
        val id = pack.id
        try {
          val deps = pack.getDeps.get
          if (deps.isEmpty) {
            map.update(id, PackageNode(id, Seq.empty, scala.collection.mutable.Set.empty))
          } else {
            val depsx = new scala.collection.mutable.ArrayBuffer[Int](deps.size)
            var failed = false
            var k = deps.size - 1
            val seq = deps.toSeq
            while (!failed && k > -1) {
              val d = seq(k)
              val cache = depCache.get(d)
              if (cache.isEmpty) {
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
      val check = set.toSet
      set.clear()
      complete = true
      var total = 0
      var x = 0
      for (i <- 0 to maps.size - 1) {
        if (i % 1000000 == 0) logger.info(s"count $count , $i, total | $total")
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
          total += node.packages.size
          if (node.packages.size != current) {
            complete = false
            set += node.src
          } else {
            x += 1
          }
        }
      }
      logger.info(s"count :$count, x: ${x}, total: $total")
      count += 1
      first = false
    }
    logger.info("complete!")
  }

  def latestP(vers: Seq[SourcePackage], condition: String): Option[SourcePackage] = {
    Try {
      val range = Range(condition)
      vers
        .filter(x => range.valid(SemVer(x.version)))
        .sortWith((a, b) => SemVer(a.version) > SemVer(b.version))
        .headOption
    }.getOrElse(None)
  }

  case class PackageNode(
      src: Int,
      directed: Seq[Int],
      packages: scala.collection.mutable.Set[Int]
  )
}
