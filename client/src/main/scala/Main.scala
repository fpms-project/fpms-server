import cats.data.NonEmptyList
import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.IOApp
import cats.implicits._
import fpms.PackageInfo
import io.circe.syntax._
import org.http4s._
import org.http4s.client.blaze.BlazeClientBuilder
import org.http4s.client.dsl.Http4sClientDsl
import org.http4s.dsl.io._
import scala.concurrent.ExecutionContext.global
import scala.io.Source
import io.circe.parser.decode
import org.http4s.client.Client


object Main extends IOApp with Http4sClientDsl[IO] {

  import org.http4s.circe.CirceEntityEncoder._

  var alreadyRequested: Seq[String] = Seq.empty

  val uri = Uri.uri("http://localhost:8080/add_package")

  override def run(args: List[String]): IO[ExitCode] = {
    val pack = PackageInfo("b", "1.0.0", Map("a" -> "^1.0.0"))
    val v = POST(pack.asJson, uri)
    BlazeClientBuilder[IO](global).resource.use[String] { httpClient =>
      httpClient.expect[String](v)
    }.as(ExitCode.Success)
  }

  def request(client: Client[IO],packs: NonEmptyList[PackageInfo]) = {
    packs.map(p => POST(p.asJson, uri)).map(r => client.expect[String](r)).parSequence_
  }

  def readFile(filename: String): String = {
    val source = Source.fromFile(filename)
    val result = source.getLines.mkString
    source.close()
    result
  }

  def parse(src: String) = decode[List[RootInterface]](src)

  def firstRequest(list: List[RootInterface]) = {
    list.filter(_.versions.forall(_.dep.isEmpty))
  }

  def toPacks(list: List[RootInterface]):NonEmptyList[PackageInfo] = NonEmptyList.fromList(list.flatMap(x => x.versions.map(e => PackageInfo(x.name, e.version, e.dep)))).get

}
