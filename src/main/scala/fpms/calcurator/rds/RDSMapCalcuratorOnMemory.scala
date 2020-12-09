package fpms.calcurator.rds

import scala.concurrent.ExecutionContext

import cats.Parallel
import cats.effect.ConcurrentEffect
import cats.effect.concurrent.MVar
import cats.effect.concurrent.MVar2
import cats.effect.concurrent.Semaphore
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging

import fpms.calcurator.ldil.LDILMap

class RDSMapCalcuratorOnMemory[F[_]](implicit F: ConcurrentEffect[F], P: Parallel[F])
    extends RDSMapCalcurator[F]
    with LazyLogging {

  def calc(ldilMap: LDILMap): F[RDSMap] = {
    val initedMap = initMap(ldilMap)
    val allMap = initedMap._1
    val allMapList = allMap.toList
    var updated = initedMap._2
    // Loop
    val semaphor = F.toIO(Semaphore.apply(32)).unsafeRunSync()
    while (updated.nonEmpty) {
      logger.info(s"updated size: ${updated.size}")
      val updateInLoop = F.toIO(MVar.of[F, Set[Int]](Set.empty[Int])).unsafeRunSync()
      val lock = F.toIO(MVar.of[F, Unit](())).unsafeRunSync()
      F.toIO(lock.take).unsafeRunSync()
      val count = F.toIO(MVar.of[F, Int](0)).unsafeRunSync()
      val checkFunction: (Int => Boolean) =
        if (updated.size / ldilMap.size > 0.5) { (_) => true }
        else updated.contains
      // 最初からSemaphoreでやってくれるようなやつを作る必要がある
      allMapList.foreach {
        case (id, set) => {
          F.toIO(semaphor.acquire).unsafeRunSync()
          F.toIO({
              val oldSize = set.size
              ldilMap.get(id).collect { value =>
                value.foreach { tid =>
                  if (checkFunction(tid)) {
                    set ++= allMap.get(tid).get
                  }
                }
              }
              for {
                _ <- if (set.size > oldSize) updateInLoop.take.flatMap(list => updateInLoop.put(list + id))
                else F.pure(())
                c <- count.take
                _ <- F.pure(if (c % 1000000 == 0) logger.info(s"$c"))
                _ <- if (c + 1 >= allMap.size) lock.put(()) else F.pure(())
                _ <- count.put(c + 1)
                _ <- semaphor.release
              } yield ()
            })
            .unsafeRunAsyncAndForget()
        }
      }
      // lockから取れるようになるまで待つ
      F.toIO(lock.take).unsafeRunSync()
      updated = F.toIO(updateInLoop.read).unsafeRunSync()
    }
    F.pure(allMap)
  }

  private def initMap(ldilMap: LDILMap): (Map[Int, scala.collection.mutable.Set[Int]], Set[Int]) = {
    val allMap = scala.collection.mutable.Map.empty[Int, scala.collection.mutable.Set[Int]]
    val updatedIni = scala.collection.mutable.TreeSet.empty[Int]
    ldilMap.toList.map {
      case (id, set) => {
        if (set.nonEmpty) {
          allMap.update(id, scala.collection.mutable.Set(set: _*))
          updatedIni += id
        }
      }
    }
    (allMap.toMap, updatedIni.toSet)
  }
}
