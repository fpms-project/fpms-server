package fpms.calculator.ldil

import scala.util.Try

import retry._
import cats.Parallel
import cats.effect.ConcurrentEffect
import cats.effect.ContextShift
import cats.effect.concurrent.MVar2
import cats.implicits._
import com.github.sh4869.semver_parser
import com.typesafe.scalalogging.LazyLogging
import scala.concurrent.duration._

import fpms.LibraryPackage
import fpms.LDIL.LDILMap
import fpms.repository.LDILRepository
import fpms.repository.LibraryPackageRepository
import retry.RetryDetails.GivingUp
import retry.RetryDetails.WillDelayAndRetry

class LDILMapCalculatorWithRedis[F[_]](
    packageRepo: LibraryPackageRepository[F],
    ldilRepo: LDILRepository[F],
    mvar: MVar2[F, Map[Int, Seq[Int]]]
)(implicit F: ConcurrentEffect[F], P: Parallel[F], cs: ContextShift[F], s: Sleep[F])
    extends LDILMapCalculator[F]
    with LazyLogging {
  import VersionFinder._

  def init: F[LDILMap] = new LDILMapCalculatorOnMemory[F].init

  def update(adds: Seq[LibraryPackage]): F[LDILMap] = {
    // 追加されたパッケージをここでソートする。
    // 例えばあるパッケージAの3.0.0と2.0.0が同時に追加された場合、より新しい方に更新する必要がある
    val sortedAdds = adds.sortWith((a, b) => if (a.name == b.name) a.version < b.version else a.name > b.name)
    val ldilMap = for {
      // ldil mapを取得する
      beforeMap <- getOrCreateLdilMap()
      _ <- F.pure(logger.info("get before ldil"))
      // 追加されたパッケージについてのLDILを作成する
      added <- sortedAdds.map(v => getLDIL(v)).toList.parSequence.map(_.flatten.toMap)
      _ <- F.pure(logger.info("create ldil list for added packages"))
      // 追加されたパッケージによって更新されるLDILを計算する
      idToUpdatePairMap <- sortedAdds.map(v => createShouldUpdateList(v, beforeMap)).parSequence.map(combineUpdateList)
      _ <- F.pure(logger.info("all calc end"))
    } yield {
      // 更新するべきリストを使って計算を行う
      val updateLdil = idToUpdatePairMap.map {
        case (id, updateList) =>
          id -> (beforeMap.get(id).get.filter(v => !updateList.keySet.contains(v)) ++ updateList.values).toList
      }
      beforeMap ++ added ++ updateLdil
    }
    for {
      v <- ldilMap
      // 二回目以降はredisにアクセスしなくていいように
      _ <- mvar.tryTake
      _ <- mvar.put(v)
    } yield v
  }

  private def getLDIL(p: LibraryPackage): F[Option[(Int, Seq[Int])]] = {
    try {
      p.deps.map {
        case (name, cond) =>
          packageRepo.findByName(name).map(_.latestInFits(cond).toOption.map(_.id))
      }.toSeq.sequence.map(seq => Some(p.id -> seq.flatten))
    } catch {
      case _: Throwable => F.pure(None)
    }
  }

  /**
    * pとldilMapから更新すべきパッケージの一覧を取得する
    *
    * 返り値のMap[Int, Map[Int, Int]]はどういう値かというと
    * Map[変更する対象のパッケージのID(A), Map[AのLDILの中から取り除くID, AのLDILの中に新たに追加するID]]
    * となっている。
    * 最初のvalueはMapとはなっているが、基本的には要素1である。
    *
    * @param target
    * @param lidl
    * @param added
    * @return
    */
  private def createShouldUpdateList(
      p: LibraryPackage,
      ldilMap: Map[Int, Seq[Int]]
  ): F[Map[Int, Map[Int, Int]]] = {
    for {
      _ <- F.pure(logger.info(s"create should update list: ${p.name}@${p.version.original}"))
      // p.name のパッケージに依存しそのパッケージのバージョン条件を p.version が満たすものを探す
      ts <- packageRepo.findByDeps(p.name).map(v => filterAcceptableNewVersion(p, v))
      _ <- F.pure(logger.info(s"get candide list of ${p.name}@${p.version.original} / ${ts.length}"))
      // TODO: リファクタリング
      updateList <- {
        // もし候補が存在しなかったらなし
        if (ts.size > 0) return F.pure(Map.empty[Int, Map[Int, Int]])
        // 存在した場合はそれぞれについてUpdatePairを作る
        val grouped = ts.grouped(ts.size / 16)
        val createUpdateList = (v: LibraryPackage) => {
          val ldil = ldilMap.get(v.id)
          if (ldil.isDefined) {
            retryingOnAllErrors(retry, onError = onError)(findAddAndRemovePair(v, ldil.get, p))
          } else {
            F.pure[Option[(Int, Map[Int, Int])]](None)
          }
        }
        grouped.map { group =>
          F.async[Map[Int, Map[Int, Int]]](cb => {
            val z = group.map(createUpdateList).sequence.map(_.flatten.toMap)
            cb(Right(F.toIO(z).unsafeRunSync()))
          })
        }.toSeq.parSequence.map(_.flatten.toMap)
      }
    } yield updateList
  }

  private def findAddAndRemovePair(
      target: LibraryPackage,
      lidl: Seq[Int],
      added: LibraryPackage
  ): F[Option[(Int, Map[Int, Int])]] = {
    ldilPackages(lidl).map { deps =>
      // LDILの中でpと同じ名前のパッケージを探し、そのバージョンがp.versionより古かった場合のみ更新する(headOptionにしているのはそのため)
      val before = deps.filter(t => t.name == added.name && t.version < added.version).headOption
      before.map(z => target.id -> Map(z.id -> added.id))
    }
  }

  private def ldilPackages(lidl: Seq[Int]): F[List[LibraryPackage]] = packageRepo.findByIds(lidl.toList)

  /**
    * pをdirectly-dependencyとして受け入れることのできるパッケージをlistから抽出する
    */
  private def filterAcceptableNewVersion(p: LibraryPackage, list: Seq[LibraryPackage]) =
    list.filter(_.deps.get(p.name).exists(v => Try { semver_parser.Range(v).valid(p.version) }.getOrElse(false)))

  private def getOrCreateLdilMap(): F[Map[Int, Seq[Int]]] =
    mvar.isEmpty.flatMap(empty =>
      if (empty) {
        for {
          max <- packageRepo.getMaxId()
          z <- ldilRepo.get((0 to max).toSeq)
        } yield z
      } else {
        mvar.read
      }
    )

  private def combineUpdateList(list: Seq[Map[Int, Map[Int, Int]]]): Map[Int,Map[Int,Int]] = {
    list.reduce((a, b) =>
      // あとから足したものに上書きされるのでこの順序でOK
      (a.keySet ++ b.keySet)
        .map(key => key -> (a.get(key).getOrElse(Map.empty) ++ b.get(key).getOrElse(Map.empty)))
        .toMap
    )
  }

  private def onError(err: Throwable, details: RetryDetails): F[Unit] = details match {
    case GivingUp(totalRetries, totalDelay) =>
      F.pure(logger.error(s"Error on ${totalRetries} times in ${totalDelay}: ", err))
    case WillDelayAndRetry(_, _, _) =>
      F.pure(())
  }

  private lazy val retry = RetryPolicies.constantDelay[F](300.millisecond)

}
