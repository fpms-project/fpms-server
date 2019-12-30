package fpms

import cats.data.EitherT
import cats.effect.ConcurrentEffect
import cats.effect.IO
import cats.effect.concurrent.MVar
import fs2.concurrent.Queue
import io.circe.generic.auto._
import io.circe.parser.decode
import org.slf4j.LoggerFactory
import scala.io.Source

class JsonLoader(topicManager: TopicManager[IO], packageUpdateSubscriberManager: PackageUpdateSubscriberManager[IO])(implicit c: ConcurrentEffect[IO]) {
  private val logger = LoggerFactory.getLogger(this.getClass)
  private var already: Seq[String] = Seq.empty

  def initialize(fileCount: Int = 1, max: Int = 5) = {
    var list = createLists(fileCount).slice(0, 1000)
    val firstList = list.filter(_.versions.forall(!_.dep.exists(_.nonEmpty)))
    var remainList: Seq[Seq[PackageInfo]] = Seq.empty
    firstList.foreach(v => {
      val result = addPackageContainer_(v)
      packageUpdateSubscriberManager.addNewSubscriber(result._1).unsafeRunSync()
      already = already :+ v.name
      if(result._2.nonEmpty){
        remainList :+ result._2
      }
      logger.info(s"[JSONLOADER] add: ${v.name}")
    })
    var count = 0
    while ((list.nonEmpty || remainList.nonEmpty) && count < max) {
      count += 1
      logger.info(s"[JSONLOADER] count: $count")
      list = list.filter(e => !already.contains(e.name))
      list.foreach(v => {
        val result = addPackageContainer_(v)
        packageUpdateSubscriberManager.addNewSubscriber(result._1).unsafeRunSync()
        already = already :+ v.name
        if(result._2.nonEmpty){
          remainList = remainList :+ result._2
        }
        logger.info(s"[JSONLOADER] add: ${v.name}, all: ${v.versions.length}, remain: ${result._2.length}")
      })
      remainList = remainList.map(e => {
        val result = addRemainPackages(e)
        logger.info(s"[JSONLOADER] add remain: ${e.head.name}, ${e.length} -> ${result.length}")
        result
      }).filter(_.nonEmpty)
    }
  }

  private def addPackageContainer_(rootInterface: RootInterface): (PackageUpdateSubscriber[IO], Seq[PackageInfo]) = {
    val name = rootInterface.name
    val queue = Queue.bounded[IO, PackageUpdateEvent](100).unsafeRunSync()
    val topic = topicManager.addNewNamePackage(name).unsafeRunSync()
    val containers = rootInterface.versions.map(e => {
      val packageInfo = PackageInfo(name, e.version, e.dep)
      val result = for {
        latests <- packageUpdateSubscriberManager.getLatestsOfDeps(e.dep.getOrElse(Map.empty))
        maps <- packageUpdateSubscriberManager.calcuratePackageDependeincies(latests)
        d <- EitherT.right[Any](MVar.of[IO, Map[String, PackageInfo]](latests.mapValues(_.info)))
        x <- EitherT.right[Any](MVar.of[IO, Map[String, Seq[PackageInfo]]](maps))
      } yield new PackageDepsContainer[IO](packageInfo, d, x)
      result.value.map(_.left map { _ => packageInfo })
    }).map(_.unsafeRunSync())
    val allDeps = rootInterface.versions.flatMap(_.dep.fold(Seq.empty[String])(_.keys.toSeq)).distinct
    allDeps.foreach(d => topicManager.subscribeTopic(d, queue).unsafeRunAsyncAndForget())
    val mvar = MVar.of[IO, Seq[PackageDepsContainer[IO]]](containers.collect { case Right(e) => e }).unsafeRunSync()
    val alreadyS = MVar.of[IO, Seq[String]](allDeps).unsafeRunSync()
    val subscriber = new PackageUpdateSubscriber[IO](name, System.currentTimeMillis, mvar, queue, topic, alreadyS)
    subscriber.deleteAllinQueue()
    subscriber.start.unsafeRunAsyncAndForget()
    (subscriber, containers.collect { case Left(e) => e })
  }

  private def addRemainPackages(packages: Seq[PackageInfo]): Seq[PackageInfo] = {
    packages.map(p => (p, packageUpdateSubscriberManager.addNewPackage(p))).map(v => v._2.value.unsafeRunSync().left map { _ => v._1 }).collect { case Left(e) => e }
  }

  private def filepath(count: Int): String = s"/run/media/sh4869/SSD/result2/$count.json"

  private def createLists(count: Int): Seq[RootInterface] = {
    var lists = Seq.empty[Option[List[RootInterface]]]
    for (i <- 0 to count) {
      lists = lists :+ parse(readFile(filepath(i)))
    }
    lists.flatten.flatten[RootInterface]
  }

  private def readFile(filename: String): String = {
    val source = Source.fromFile(filename)
    val result = source.getLines.mkString
    source.close()
    result
  }

  private def parse(src: String): Option[List[RootInterface]] = decode[List[RootInterface]](src).toOption

  private def firstRequest(list: Seq[RootInterface]): Seq[RootInterface] = list.filter(_.versions.forall(_.dep.isEmpty))
}
