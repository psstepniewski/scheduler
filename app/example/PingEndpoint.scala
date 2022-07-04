package example

import akka.actor.ActorSystem
import akka.actor.typed.Scheduler
import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.scaladsl.adapter.ClassicActorSystemOps
import akka.util.Timeout
import example.PingActor.Pong
import play.api.Logging
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt

@Singleton
class PingEndpoint @Inject()(actorSystem: ActorSystem, cc: ControllerComponents)(implicit ec: ExecutionContext, scheduler: Scheduler)
  extends AbstractController(cc)
  with Logging {

  implicit val timeout: Timeout = 30.seconds

  def call(): Action[AnyContent] = Action.async { implicit request =>
    logger.debug(s"PingEndpoint: request received")
    actorSystem
      .spawnAnonymous(PingActor())
      .ask(replyTo => PingActor.Ping(replyTo))
      .map{
        case Pong =>
          Accepted("Ponged")
      }
      .recover{
        case ex =>
          logger.error(s"PingEndpoint: returning 500.", ex)
          InternalServerError(ex.getMessage)
      }
  }
}
