package package_manager_server

import cats.effect.Concurrent
import cats.effect.concurrent.MVar
import fs2.concurrent.Queue
import fs2.concurrent.Topic
import fs2.Stream
import cats.implicits._

class TopicManager[F[_] : Concurrent](private val topicMap: MVar[F, Map[String, Topic[F, PackageUpdateEvent]]]) {
  def subscribeTopic(packageName: String, queue: Queue[F, PackageUpdateEvent]) =
    Stream.eval(topicMap.read)
      .map({
        _.get(packageName)
      })
      .collect { case Some(e) => e }
      .flatMap(input => {
        input.subscribe(10).through(queue.enqueue)
      }).compile.drain

  def addNewNamePackage(pack: PackageInfo) = for {
    topic <- Topic[F, PackageUpdateEvent](AddNewNamePackage(pack.name))
    m <- topicMap.take.map(_.updated(pack.name, topic))
    _ <- topicMap.put(m)
  } yield topic

}
