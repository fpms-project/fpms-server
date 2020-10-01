package fpms

import com.redis.RedisClient
import com.redis.serialization.Parse.Implicits._
import fpms.repository.SourcePackageRepository
import fpms.json.JsonLoader
import org.slf4j.LoggerFactory
import scala.util.Try
import cats.effect.ConcurrentEffect

class RedisDependecyCalculator[F[_]](redis: RedisClient, spRepo: SourcePackageRepository[F])(implicit F: ConcurrentEffect[F])
    extends DependencyCalculator {

  private val logger = LoggerFactory.getLogger(this.getClass)

  def initialize(): Unit = {
    redis.flushall
    val x = new LocalDependencyCalculator
    x.initialize()
    saveInitializeList(x.getMap)
  }

  private def saveInitializeList(map: Map[Int, PackageNode]) {
    map.foreach(v => {
      val id = v._1
      if (v._2.directed.size > 1) {
        redis.lpush(directedKey(id), v._2.directed.head, v._2.directed.tail: _*)
      } else if (v._2.directed.size == 1) {
        redis.lpush(directedKey(id), v._2.directed.head)
      }
      redis.sadd(packagesKey(id), id, v._2.packages.toSeq: _*)
    })
  }

  def get(id: Int): Option[PackageNode] = {
    for {
      d <- redis.lrange[Int](directedKey(id), 0, -1)
      p <- redis.smembers[Int](packagesKey(id))
    } yield PackageNode(id, d.flatten, scala.collection.mutable.Set[Int](p.flatten.toSeq: _*))
  }

  def load(): Unit = ???

  def add(added: Seq[SourcePackageInfo]): Unit = ???

  private def directedKey(id: Int) = s"directed_${id}"
  private def packagesKey(id: Int) = s"packages_${id}"

  implicit class IntOption(src: String) {
    def toIntOption: Option[Int] = Try { Some(src.toInt) }.getOrElse(None)
  }
}
