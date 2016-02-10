package controllers

import akka.stream.scaladsl.Source
import play.api.mvc._
import scala.concurrent.duration._
import play.api.libs.json._
import play.api.libs.ws._
import akka.util._
import akka.stream.actor._
import akka.stream.scaladsl._
import play.api.Play.current
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import akka.stream.io.Framing
import play.api.libs.EventSource

case class TweetInfo(searchQuery: String, message: String, author: String) {
  def toJsonString = Json.stringify(Json.obj("message" -> s"${this.searchQuery} : ${this.message}", "author" -> s"${this.author}"))
}

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

  //fake twitter API
  def timeline(keyword: String) = Action {
    val source = Source.tick(initialDelay = 0 second, interval = 1 second, tick = "tick")
    Ok.chunked(source.map { tick =>
      val (prefix, author) = prefixAndAuthor
      Json.obj("message" -> s"$prefix $keyword", "author" -> author).toString + "\n"
    }.limit(100))
  }


  def stream(query: String) = Action.async {
    val sourceListFuture = query.split(",").toList.map { query =>
      val futureTwitterResponse = WS.url(s"http://localhost:9000/timeline/$query").stream
      futureTwitterResponse.map { response =>
        response.body.via(Framing.delimiter(ByteString("\n"), maximumFrameLength = 100, allowTruncation = true).map(_.utf8String)).map { tweet =>
          val json = Json.parse(tweet)
          TweetInfo(query, (json \ "message").as[String], (json \ "author").as[String])
        }
      }
    }

    val sourceFuture = Future.sequence(sourceListFuture).map(Source(_).flatMapMerge(10, identity).map(_.toJsonString))
    sourceFuture.map { source =>
      Ok.chunked(source via EventSource.flow)
    }
  }

}
