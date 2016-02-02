package controllers

import akka.stream.scaladsl.Source
import play.api.mvc._
import scala.concurrent.duration._
import play.api.libs.json._
import play.api.libs.ws._
import org.reactivestreams._
import akka.actor._
import akka.stream.actor._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl._
import play.api.Play.current
import scala.util._
import play.api.Logger
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.collection._

class Application extends Controller {

  def index = Action {
    //default search
    Redirect(routes.Application.liveTouits(List("java", "ruby")))
  }

  def liveTouits(queries: List[String]) = Action {
    Ok(views.html.index(queries))
  }

  private def prefixAndAuthor = {
    import java.util.Random
    val prefixes = List("Tweet about", "Just heard about", "I love")
    val authors = List("Bob", "Joe", "John")
    val rand = new Random()
    (prefixes(rand.nextInt(prefixes.length)), authors(rand.nextInt(authors.length)))
  }

  def timeline(keyword: String) = Action {
    val source = Source.tick(initialDelay = 0 second, interval = 1 second, tick = "tick")
    val (prefix, author) = prefixAndAuthor
    Ok.chunked(source.map { tick =>
      val (prefix, author) = prefixAndAuthor
      Json.obj("message" -> s"$prefix $keyword", "author" -> author).toString + "\n"
    }.limit(100)).as("application/json")
  }

  //fake twitter API
  def stream(query: String) = Action.async {
    val sources = query.split(",").toList.map { query =>
      val twitterStreamSource = WS.url(s"http://localhost:9000/timeline/$query").stream

      twitterStreamSource.map { response =>
        response.body.mapConcat {
          byteString => byteString.decodeString("UTF-8").split("\n").toList.map { response =>
            val json = Json.parse(response)
            TweetInfo(query, (json \ "message").as[String], (json \ "author").as[String])
          }
        }
      }
    }

    val source = Future.sequence(sources).map(Source(_).flatMapMerge(10, identity))
    source.map(source => Ok.chunked(source.map(_.toJson))) //.as("text/event-stream")
  }


  def stream2(queries: String) = Action {
    implicit val system = ActorSystem("touiter")
    implicit val materializer = ActorMaterializer()

    val actorRef = system.actorOf(Props[ActorBasedTwitterSource])
    val publisher = ActorPublisher[TweetInfo](actorRef)

    queries.split(",").foreach { query =>
      val twitterStreamSource = WS.url(s"http://localhost:9000/timeline/$query").stream

      twitterStreamSource.onComplete {
        case Success(source) => source.body.map(_.decodeString("UTF-8")).runForeach { line =>
          Logger.debug(line)
          actorRef !(query, line)
        }
        case Failure(t) => Logger.error("An error has occured: ", t)
      }

    }
    val source = Source.fromPublisher(publisher)
    //hack for SSE before integration in Play
    val sseSource = Source.single("event: message\n").concat(source.map(tweetInfo => s"data: ${tweetInfo.toJson}\n\n"))
    Ok.chunked(sseSource).as("text/event-stream")
  }
}

case class TweetInfo(searchQuery: String, message: String, author: String) {
  def toJson = Json.obj("message" -> s"${this.searchQuery} : ${this.message}", "author" -> s"${this.author}")
}

class ActorBasedTwitterSource extends Actor with ActorPublisher[TweetInfo] {

  import ActorPublisherMessage._
  import mutable.Map
  import mutable.ListBuffer

  val buffers = Map[String, String]()
  val items: ListBuffer[TweetInfo] = ListBuffer.empty

  def receive = {
    case (query: String, responses: String) =>
      val queryBuffer = buffers.getOrElse(query, "")
      val currentString = (queryBuffer + responses)
      if (currentString contains "\n") {
        val twitterResponses = currentString.split("\n")
        buffers(query) = ""
        val tweetInfos = twitterResponses.map { response =>
          val json = Json.parse(response)
          TweetInfo(query, (json \ "message").as[String], (json \ "author").as[String])
        }
        if (totalDemand == 0) {
          items ++= tweetInfos
        }
        else {
          tweetInfos foreach (onNext)
        }
      }

    case Request(demand) =>
      if (demand > items.size) {
        items foreach (onNext)
        items.clear
      }
      else {
        val (send, _) = items.splitAt(demand.toInt)
        send.foreach { elem =>
          items -= elem
          onNext(elem)
        }
      }

    case Cancel =>
      items.clear
      println("Canceled")

    case other =>
      println(s"got other $other")
  }

}
