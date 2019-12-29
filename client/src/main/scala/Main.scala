import cats.data.NonEmptyList
import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.IOApp
import cats.implicits._
import fpms.PackageInfo
import io.circe.generic.auto._
import io.circe.parser.decode
import io.circe.syntax._
import org.http4s._
import org.http4s.client.Client
import org.http4s.client.blaze.BlazeClientBuilder
import org.http4s.client.dsl.Http4sClientDsl
import org.http4s.dsl.io._
import scala.concurrent.ExecutionContext.global
import scala.concurrent.duration._
import scala.io.Source


object Main extends IOApp with Http4sClientDsl[IO] {

  import org.http4s.circe.CirceEntityEncoder._

  var alreadyRequested: Seq[String] = Seq.empty

  val endpoint = Uri.uri("http://localhost:8080/add_package")

  def filepath(count: Int):String = s"/run/media/sh4869/SSD/result2/$count.json"

  override def run(args: List[String]): IO[ExitCode] = {
    BlazeClientBuilder[IO](global).resource.use[Unit](httpClient => {
      for {
        _ <- IO(println("parsing file"))
        lists <- IO(createLists(3).toList.slice(0, 3000))
        firsts <- IO(firstRequest(lists))
        _ <- IO(println(s"creating first request: ${firsts.length}"))
        _ <- requests(httpClient, toPacks(firsts), 10)
        retains <- IO({
          val firstnames = firsts.map(_.name)
          toPacks(lists.filter(e => !firstnames.contains(e.name)))
        })
        _ <- IO(println(s"creating retain request(1): ${retains.length}"))
        v <- cdrRequest(retains).map(x => requests(httpClient, x)).getOrElse(IO(List.empty[String]))
        retains <- IO({alreadyRequested ++= v; retains.filter(e => !alreadyRequested.contains(packToString(e)))})
        _ <- IO(println(s"creating retain request(2): ${retains.length}"))
        v <- retains.toNel.map(x => requests(httpClient, x)).getOrElse(IO(List.empty[String]))
        retains <- IO({alreadyRequested ++= v; retains.filter(e => !alreadyRequested.contains(packToString(e)))})
        _ <- IO(println(s"creating retain request(3): ${retains.length}"))
        v <- retains.toNel.map(x => requests(httpClient, x)).getOrElse(IO(List.empty[String]))
        retains <- IO({alreadyRequested ++= v; retains.filter(e => !alreadyRequested.contains(packToString(e)))})
        _ <- IO(println(s"creating retain request(4): ${retains.length}"))
        v <- retains.toNel.map(x => requests(httpClient, x)).getOrElse(IO(List.empty[String]))
        retains <- IO({alreadyRequested ++= v; retains.filter(e => !alreadyRequested.contains(packToString(e)))})
        _ <- IO(println(s"creating retain request(5): ${retains.length}"))
        v <- retains.toNel.map(x => requests(httpClient, x)).getOrElse(IO(List.empty[String]))
      } yield ()
    }).as(ExitCode.Success)
  }

  def requests(client: Client[IO], packs: NonEmptyList[PackageInfo],count: Int = 8): IO[List[String]] = {
    packs.map(p => (POST(p.asJson, endpoint), p))
      .mapWithIndex((r, i) =>
        IO.sleep((i / count * 100).milliseconds)
          .flatMap(_ => client.expectOption[String](r._1.unsafeRunSync()).map(e => e.map[String](_ => packToString(r._2))))
      ).parSequence.map(_.collect { case Some(x) => x })
  }

  def createLists(count: Int): Seq[RootInterface] = {
    var lists = Seq.empty[Option[List[RootInterface]]]
    for(i <- 0 to count) {
      lists = lists :+ parse(readFile(filepath(i)))
    }
    lists.flatten.flatten[RootInterface]
  }

  def readFile(filename: String): String = {
    val source = Source.fromFile(filename)
    val result = source.getLines.mkString
    source.close()
    result
  }

  def parse(src: String): Option[List[RootInterface]] = decode[List[RootInterface]](src).toOption

  def firstRequest(list: List[RootInterface]): List[RootInterface] = list.filter(_.versions.forall(_.dep.isEmpty))

  def cdrRequest(list: NonEmptyList[PackageInfo]): Option[NonEmptyList[PackageInfo]] = list.filter(l => !alreadyRequested.contains(packToString(l))).toNel

  def toPacks(list: List[RootInterface]): NonEmptyList[PackageInfo] = NonEmptyList.fromList(list.flatMap(x => x.versions.map(e => PackageInfo(x.name, e.version, e.dep)))).get

  def packToString(pack: PackageInfo): String = s"${pack.name}@${pack.version.original}"
}
