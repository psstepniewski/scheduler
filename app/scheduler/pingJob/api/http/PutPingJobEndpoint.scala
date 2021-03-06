package scheduler.pingJob.api.http

import com.fasterxml.jackson.databind.JsonNode
import play.api.Logging
import play.api.libs.json._
import play.api.mvc.{AbstractController, Action, ControllerComponents}
import scheduler.pingJob.{PingJob, PingJobApi, PingJobSelector}
import scheduler.{KafkaProducer, WithJsError, controllerTimeout}

import java.time.OffsetDateTime
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class PutPingJobEndpoint @Inject()(pingJobSelector: PingJobSelector, cc: ControllerComponents)(implicit ec: ExecutionContext)
  extends AbstractController(cc)
    with Logging
    with WithJsError {

  import PutPingJobEndpoint._

  def call(pingJobId: PingJob.Id): Action[JsValue] = Action(parse.json).async { implicit request =>
    logger.debug(s"PutPingJobEndpoint[$pingJobId]: request received")
    request.body.validate[Request] match {
      case JsSuccess(v, _) =>
        pingJobSelector
          .entityRef(pingJobId)
          .ask[PingJobApi.Command.Schedule.Result](replyTo => PingJobApi.Command.Schedule(replyTo, v.pongTopic, v.pongKey, KafkaProducer.PureJson(v.pongData), v.willPongTimestamp.toInstant))
          .map {
            case PingJobApi.Command.Schedule.Result.Scheduled =>
              logger.debug(s"PutPingJobEndpoint[$pingJobId]: returning 200 (Created).")
              Ok("Created")
            case PingJobApi.Command.Schedule.Result.AlreadyScheduled =>
              logger.debug(s"PutPingJobEndpoint[$pingJobId]: returning 200 (AlreadyCreated).")
              Ok("AlreadyCreated")
            case PingJobApi.Command.Schedule.Result.ExecutedState =>
              logger.debug(s"PutPingJobEndpoint[$pingJobId]: returning 200 (ExecutedState).")
              Ok("ExecutedState")
            case PingJobApi.Command.Schedule.Result.Failure(ex) =>
              throw ex
          }
          .recover{
            case ex =>
              logger.error(s"PutPingJobEndpoint[$pingJobId]: returning 500.", ex)
              InternalServerError(ex.getMessage)
          }
      case JsError(e) =>
        logger.debug(s"PutPingJobEndpoint[$pingJobId]: returning 400 ($e).")
        Future.successful(BadRequest(toJson(e)))
    }
  }
}

object PutPingJobEndpoint {
  private case class Request(pongTopic: PingJob.TopicName, pongKey: PingJob.TopicKey, pongData: JsonNode, willPongTimestamp: OffsetDateTime)
  private implicit val topicNameReads: Reads[PingJob.TopicName] = Json.valueReads[PingJob.TopicName]
  private implicit val topicKeyReads: Reads[PingJob.TopicKey] = Json.valueReads[PingJob.TopicKey]
  private implicit val requestReads: Reads[Request] = Json.reads[Request]
}
