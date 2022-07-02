import akka.util.Timeout
import com.fasterxml.jackson.annotation.JsonInclude.Include
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import play.api.Logging
import play.api.i18n.{Lang, Messages, MessagesImpl}
import play.api.libs.json._
import play.api.mvc.BaseController

import java.text.SimpleDateFormat
import java.time.ZoneId
import java.util.TimeZone
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success, Try}

package object scheduler {

  implicit val controllerTimeout: Timeout = 40.seconds
  implicit val actorAskTimeout: Timeout = 35.seconds

  private val INCOMING_DATE_FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
  private val UTC_ZONE_ID = "UTC"

  val objectMapper: ObjectMapper = new ObjectMapper()
    .registerModule(DefaultScalaModule)
    .registerModule(new JavaTimeModule())
    .setDateFormat(new SimpleDateFormat(INCOMING_DATE_FORMAT))
    .setTimeZone(TimeZone.getTimeZone(ZoneId.of(UTC_ZONE_ID)))
    .setSerializationInclusion(Include.NON_ABSENT)

  trait WithJsError {
    self: BaseController with Logging =>

    def toJson(errors: collection.Seq[(JsPath, collection.Seq[JsonValidationError])]): JsObject = {
      def toString(jsPath: JsPath): String = jsPath.path.map(_.toJsonString.substring(1)).mkString(".")

      def toJson: JsObject = {
        errors.foldLeft(JsObject.empty) { (obj, error) =>
          obj ++ JsObject(Seq(toString(error._1) -> error._2.foldLeft(JsArray.empty) { (arr, err) =>
            val msg = JsString(Messages(err.message, err.args: _*)(MessagesImpl(Lang("en"), messagesApi)))
            arr :+ msg
          }))
        }
      }

      Try(toJson) match {
        case Success(v) => v
        case Failure(e) =>
          logger.error("Generating json with error description failure!", e)
          Json.obj()
      }
    }
  }
}
