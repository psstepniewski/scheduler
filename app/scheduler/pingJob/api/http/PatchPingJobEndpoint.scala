package scheduler.pingJob.api.http

import akka.actor.typed.Scheduler
import play.api.Logging
import play.api.libs.json._
import play.api.mvc.{AbstractController, Action, ControllerComponents}
import scheduler.pingJob.{PingJob, PingJobApi, PingJobSelector}
import scheduler.{WithJsError, controllerTimeout}

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class PatchPingJobEndpoint @Inject()(pingJobSelector: PingJobSelector, cc: ControllerComponents)(implicit ec: ExecutionContext)
  extends AbstractController(cc)
    with Logging
    with WithJsError {

  import PatchPingJobEndpoint._

  def call(pingJobId: PingJob.Id): Action[JsValue] = Action.async(parse.json) { implicit request =>
    logger.debug(s"PatchPingJobEndpoint[$pingJobId]: request received")
    request.body.validate[Request] match {
      case JsSuccess(v, _) =>
        v.stateName match {
          case PingJob.StateName.Empty =>
            logger.debug(s"PatchPingJobEndpoint[$pingJobId]: returning 400 (Not allowed `stateName` value: ${v.stateName}, allowed values are [`${PingJob.StateName.Executed}`]).")
            Future.successful(BadRequest(s"Not allowed `stateName` value: ${v.stateName} (only allowed values are [`${PingJob.StateName.Executed}`])"))
          case PingJob.StateName.Scheduled =>
            logger.debug(s"PatchPingJobEndpoint[$pingJobId]: returning 400 (Not allowed `stateName` value: ${v.stateName}, allowed values are [`${PingJob.StateName.Executed}`]).")
            Future.successful(BadRequest(s"Not allowed `stateName` value: ${v.stateName} (only allowed values are [`${PingJob.StateName.Executed}`])"))
          case PingJob.StateName.Executed =>
            pingJobSelector
              .entityRef(pingJobId)
              .ask(replyTo => PingJobApi.Command.Execute(replyTo))
              .map {
                case PingJobApi.Command.Execute.Result.Executed =>
                  logger.debug(s"PatchPingJobEndpoint[$pingJobId]: returning 200 (Executed).")
                  Ok("Executed")
                case PingJobApi.Command.Execute.Result.EmptyState =>
                  logger.debug(s"PatchPingJobEndpoint[$pingJobId]: returning 404.")
                  NotFound
                case PingJobApi.Command.Execute.Result.AlreadyExecuted =>
                  logger.debug(s"PatchPingJobEndpoint[$pingJobId]: returning 202 (AlreadyExecuted).")
                  Accepted("AlreadyExecuted")
                case PingJobApi.Command.Execute.Result.Failure(ex) =>
                  throw ex
              }
              .recover{
                case ex =>
                  logger.error(s"PatchPingJobEndpoint[$pingJobId]: returning 500.", ex)
                  InternalServerError(ex.getMessage)
              }
        }
      case JsError(e) =>
        logger.debug(s"PatchPingJobEndpoint[$pingJobId]: returning 400 ($e).")
        Future.successful(BadRequest(toJson(e)))
    }
  }
}

object PatchPingJobEndpoint {
  case class Request(stateName: PingJob.StateName.Value)
  implicit val stateNameReads: Reads[PingJob.StateName.Value] = Reads.enumNameReads(PingJob.StateName)
  implicit val requestReads: Reads[Request] = Json.reads[Request]
}

