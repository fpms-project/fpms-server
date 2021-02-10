package fpms.server.calcurator.ldil

import scala.util.Try

import cats.Parallel
import cats.effect.ConcurrentEffect
import cats.effect.ContextShift
import cats.effect.concurrent.MVar2
import cats.effect.concurrent.Semaphore
import cats.implicits._
import com.github.sh4869.semver_parser
import com.typesafe.scalalogging.LazyLogging
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.RedisCommands

import fpms.LibraryPackage
import fpms.server.redis.RedisConf
import fpms.server.redis.RedisLog
import fpms.server.repository.LibraryPackageRepository

class LDILMapCalculatorWithRedis[F[_]](
    repo: LibraryPackageRepository[F],
    conf: RedisConf,
    mvar: MVar2[F, Map[Int, Seq[Int]]]
)(
    implicit F: ConcurrentEffect[F],
    P: Parallel[F],
    cs: ContextShift[F]
) extends LDILMapCalculator[F]
    with RedisLog[F]
    with LazyLogging {
  import VersionFinder._
  import fpms.server.redis.RedisDataConversion._
  protected val X = implicitly

  def init: F[LDILMap] = new LDILMapCalculatorOnMemory[F].init

  // TODO: コメント・実装の整理
  def update(adds: Seq[LibraryPackage]): F[LDILMap] = {
    val sortedAdds = adds.sortWith((a, b) => if (a.name == b.name) a.version < b.version else a.name > b.name)
    val result = for {
      empty <- mvar.isEmpty
      beforeMap <- if (empty) createInitialMapFromRedis() else mvar.read
      _ <- F.pure(logger.info("get before values"))
      semaphore <- Semaphore(16)
      // 追加されたパッケージについてのLDILを作成する
      added <- sortedAdds.map(v => getLDILForAddedPackage(v)).toList.parSequence.map(_.flatten.toMap)
      // 追加されたパッケージによって更新されるLDILを計算する
      x <- sortedAdds.map(v => createShouldUpdateList(v, beforeMap, semaphore)).parSequence.map {
        _.reduce { (a, b) =>
          // あとから足したものに上書きされるのでこの順序でOK
          (a.keySet ++ b.keySet)
            .map(key => key -> (a.get(key).getOrElse(Map.empty) ++ b.get(key).getOrElse(Map.empty)))
            .toMap
        }
      }
      _ <- F.pure(logger.info("all calc end"))
    } yield beforeMap ++ added ++ x.map {
      case (key, values) =>
        key -> (beforeMap.get(key).get.filter(v => !values.keySet.contains(v)) ++ values.values).toList
    }
    for {
      v <- result
      _ <- mvar.tryTake
      _ <- mvar.put(v)
    } yield v
  }

  private def getLDILForAddedPackage(p: LibraryPackage): F[Option[(Int, Seq[Int])]] = {
    try {
      p.deps.map {
        case (name, cond) =>
          repo.findByName(name).map(_.latestInFits(cond).get.id)
      }.toSeq.parSequence.map(seq => Some(p.id -> seq))
    } catch {
      case _: Throwable => F.pure(None)
    }
  }

  // 返り値のMap[Int, Map[Int, Int]]はどういう値かというと
  // Map[変更する対象のパッケージのID(A), Map[AのLDILの中から取り除くID, AのLDILの中に新たに追加するID]]
  // となっている。
  // 最初のvalueはMapとはなっているが、基本的には要素1である。
  private def createShouldUpdateList(
      p: LibraryPackage,
      idMap: Map[Int, Seq[Int]],
      semaphore: Semaphore[F]
  ): F[Map[Int, Map[Int, Int]]] = {
    for {
      _ <- F.pure(logger.info(s"create should update list: ${p.name}@${p.version.original}"))
      // p.name のパッケージに依存しそのパッケージのバージョン条件を p.version が満たすものを探す
      ts <- repo.findByDeps(p.name).map(v => filterAcceptableNewVersion(p, v))
      _ <- F.pure(logger.info(s"get candide list of ${p.name}@${p.version.original} / ${ts.length}"))
      x <- ts.map { v =>
        idMap.get(v.id).fold(F.pure[Option[(Int, Map[Int, Int])]](None)) { list =>
          for {
            _ <- semaphore.acquire
            x <- repo.findByIds(list.toList).map {
              // LDILの中でpと同じ名前のパッケージを探し、そのバージョンがp.versionより古かった場合のみ更新する(headOptionにしているのはそのため)
              // flattenでNoneの場合は潰される
              _.filter(t => t.name == p.name && t.version < p.version).headOption
              // 上に書いたとおりMap[変更する対象のパッケージのID(A), Map[AのLDILの中から取り除くID, AのLDILの中に新たに追加するID]]のもとを作る
                .map(z => v.id -> Map(z.id -> p.id))
            }
            _ <- semaphore.release
          } yield x
        }
      }.parSequence.map(_.flatten.toMap)
      _ <- F.pure(logger.info(s"end should update list: ${p.name}@${p.version.original}"))
    } yield x
  }

  /**
    * pをdirectly-dependencyとして受け入れることのできるパッケージをlistから抽出する
    */
  private def filterAcceptableNewVersion(p: LibraryPackage, list: Seq[LibraryPackage]) =
    list.filter(_.deps.get(p.name).exists(v => Try { semver_parser.Range(v).valid(p.version) }.getOrElse(false)))

  private def createInitialMapFromRedis(): F[Map[Int, Seq[Int]]] = {
    Redis[F].utf8(s"redis://${conf.host}:${conf.port}").use { cmd: RedisCommands[F, String, String] =>
      for {
        max <- repo.getMaxId
        z <- {
          val groupedRange = Range(0, max).grouped(max / 16).zipWithIndex
          groupedRange.map {
            case (v, i) =>
              F.async[Map[Int, Seq[Int]]](cb => {
                val x = v.toList
                  .grouped(100)
                  .map { v => F.toIO(getSeqFromRedis(cmd, v.map(x => s"$LDIL_REDIS_PREFIX$x").toSet)).unsafeRunSync() }
                  .reduce((a, b) => a ++ b)
                logger.info(s"end $i")
                cb(Right(x))
              })
          }.toList.parSequence
        }
      } yield z.reduce((a, b) => a ++ b)
    }
  }

  private def getSeqFromRedis(cmd: RedisCommands[F, String, String], set: Set[String]): F[Map[Int, Seq[Int]]] =
    cmd
      .mGet(set)
      .map(_.map[Int, Seq[Int]] {
        case (key, value) => key.split(LDIL_REDIS_PREFIX)(1).toInt -> value.splitToSeq
      }.toMap)
}
