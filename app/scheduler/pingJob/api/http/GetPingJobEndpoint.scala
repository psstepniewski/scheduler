package scheduler.pingJob.api.http

import com.fasterxml.jackson.databind.JsonNode
import play.api.Logging
import play.api.libs.json.Json
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}
import scheduler.pingJob.{PingJob, PingJobApi, PingJobSelector}
import scheduler.{controllerTimeout, objectMapper}

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

@Singleton
class GetPingJobEndpoint @Inject()(pingJobSelector: PingJobSelector, cc: ControllerComponents)(implicit ec: ExecutionContext)
  extends AbstractController(cc)
  with Logging {

  def call(pingJobId: PingJob.Id): Action[AnyContent] = Action.async { implicit request =>
    logger.debug(s"GetPingJobEndpoint[$pingJobId]: request received")
    pingJobSelector
      .entityRef(pingJobId)
      .ask(replyTo => PingJobApi.Command.GetSnapshot(replyTo))
      .map{
        case v: PingJobApi.Command.GetSnapshot.Result.Snapshot =>
          logger.debug(s"GetPingJobEndpoint[$pingJobId]: returning 200.")
          Ok(Json.toJson(objectMapper.valueToTree(v.value).asInstanceOf[JsonNode]))
        case PingJobApi.Command.GetSnapshot.Result.EmptyState =>
          logger.debug(s"GetPingJobEndpoint[$pingJobId]: returning 404.")
          NotFound
      }
      .recover{
        case ex =>
          logger.error(s"GetPingJobEndpoint[$pingJobId]: returning 500.", ex)
          InternalServerError(ex.getMessage)
      }
  }
}
