import cats.data.NonEmptyList
import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.IOApp
import cats.implicits._
import fpms.MultiPackageResult
import fpms.PackageInfo
import fpms.RequestCondition
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

  import fpms.VersionCondition._
  import io.circe.generic.auto._
  import org.http4s.circe.CirceEntityDecoder._
  import org.http4s.circe.CirceEntityEncoder._

  var alreadyRequested: Seq[String] = Seq.empty

  val endpoint = Uri.uri("http://localhost:8080/add_package")

  def filepath(count: Int): String = s"/run/media/sh4869/SSD/result2/$count.json"

  val firstRequest: Seq[RequestCondition] = Seq(RequestCondition("@fpms/a", "*"), RequestCondition("@fpms/b", "*"))
  val secondRequest: Seq[RequestCondition] = Seq(RequestCondition("react","^15.0.0"))

  override def run(args: List[String]): IO[ExitCode] = {
    BlazeClientBuilder[IO](global).resource.use[Unit](httpClient => {
      for {

        result <- newmethod(firstRequest, httpClient)
        result <- IO(existingMethod(firstRequest, httpClient))
        result <- newmethod(firstRequest, httpClient)
        result <- IO(existingMethod(firstRequest, httpClient))
        _ <- IO(println(s"first:${firstRequest}"))
        past <- IO(System.currentTimeMillis())
        result <- newmethod(firstRequest, httpClient)
        n <- IO(System.currentTimeMillis())
        _ <- IO(println(s"${n - past}mill, request: 1"))
        past2 <- IO(System.currentTimeMillis())
        result <- IO(existingMethod(firstRequest, httpClient))
        n2 <- IO(System.currentTimeMillis())
        _ <- IO(println(s"${n2 - past2}mili, requst: ${result._2}"))
        past <- IO(System.currentTimeMillis())
        _ <- IO(println(s"first:${secondRequest}"))
        past <- IO(System.currentTimeMillis())
        result <- newmethod(secondRequest, httpClient)
        n <- IO(System.currentTimeMillis())
        _ <- IO(println(s"${n - past}mill, request: 1"))
        past2 <- IO(System.currentTimeMillis())
        result <- IO(existingMethod(secondRequest, httpClient))
        n2 <- IO(System.currentTimeMillis())
        _ <- IO(println(s"${n2 - past2}mili, requst: ${result._2}"))
        past <- IO(System.currentTimeMillis())
      } yield ()
    }).as(ExitCode.Success)
  }


  def existingMethod(request: Seq[RequestCondition], client: Client[IO]) = {
    val r = uri(request)
    val result = client.expect[Map[String, Seq[PackageInfo]]](r).unsafeRunSync()
    var latests = getlatests(request, result)
    var set: Set[PackageInfo] = latests.toSet
    var loop: Boolean = true
    var counter = 1
    while (loop) {
      val nrequest = latests.flatMap(_.dep.toList.map(e => RequestCondition(e._1, e._2)))
      if (nrequest.isEmpty) {
        loop = false
      }
      counter += 1
      val r = uri(nrequest)
      val result = client.expect[Map[String, Seq[PackageInfo]]](r).unsafeRunSync()
      latests = getlatests(nrequest, result)
      val before = set.size
      set = set ++ latests.toSet
      if (before == set.size) {
        loop = false
      }
    }
    (set, counter)
  }

  def uri(r: Seq[RequestCondition]) = Uri.unsafeFromString(s"http://localhost:8080/packages?${r.map(e => s"name=${e.name}").mkString("&")}")

  def getlatests(requests: Seq[RequestCondition], result: Map[String, Seq[PackageInfo]]): Seq[PackageInfo] =
    requests.map(e => result(e.name).filter(v => e.condition.valid(v.version)).sortWith((x, y) => x.version > y.version).head)


  def newmethod(request: Seq[RequestCondition], client: Client[IO]) = {
    val r = POST(request.asJson, Uri.uri("http://localhost:8080/get_deps"))
    client.expect[MultiPackageResult](r)
  }


  def requests(client: Client[IO], packs: NonEmptyList[PackageInfo], count: Int = 8): IO[List[String]] = {
    packs.map(p => (POST(p.asJson, endpoint), p))
      .mapWithIndex((r, i) =>
        IO.sleep((i / count * 100).milliseconds)
          .flatMap(_ => client.expectOption[String](r._1.unsafeRunSync()).map(e => e.map[String](_ => packToString(r._2))))
      ).parSequence.map(_.collect { case Some(x) => x })
  }

  def createLists(count: Int): Seq[RootInterface] = {
    var lists = Seq.empty[Option[List[RootInterface]]]
    for (i <- 0 to count) {
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
