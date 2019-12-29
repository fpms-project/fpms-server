package fpms

import java.util.concurrent.Executors
import cats.effect.Concurrent
import cats.effect.ConcurrentEffect
import cats.effect.concurrent.MVar
import cats.implicits._
import fs2.Stream
import fs2.concurrent.Queue
import fs2.concurrent.Topic
import scala.concurrent.ExecutionContext

class TopicManager[F[_] : Concurrent](private val topicMap: MVar[F, Map[String, Topic[F, PackageUpdateEvent]]])(implicit f: ConcurrentEffect[F]) {

  implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(Executors.newCachedThreadPool())

  def subscribeTopic(packageName: String, queue: Queue[F, PackageUpdateEvent]) =
    Stream.eval(topicMap.read)
      .map({
        _.get(packageName)
      })
      .collect { case Some(e) => e }
      .flatMap(input => {
        input.subscribe(1000).through(queue.enqueue)
      }).compile.drain

  def addNewNamePackage(pack: PackageInfo) = for {
    topic <- Topic[F, PackageUpdateEvent](AddNewNamePackage(pack.name))
    m <- topicMap.take.map(_.updated(pack.name, topic))
    _ <- topicMap.put(m)
  } yield topic

}