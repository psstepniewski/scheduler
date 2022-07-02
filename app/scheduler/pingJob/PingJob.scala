package scheduler.pingJob

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior, Scheduler}
import akka.util.Timeout
import scheduler.pingJob.quartz.QuartzAdapter
import scheduler.pingJob.states.EmptyPingJob
import scheduler.{CborSerializable, KafkaProducer}

import java.time.Instant
import scala.concurrent.duration.DurationInt

object PingJob {

  import PingJobApi._

  private implicit val actorAskTimeout: Timeout = 30.seconds

  case class Id(value: String) extends AnyVal
  case class TopicName(value: String) extends AnyVal
  case class TopicKey(value: String) extends AnyVal

  case class Snapshot[A <: KafkaProducer.SerializableMessage](id: Id, stateName: StateName.Value, pongTopic: TopicName, pongKey: TopicKey, pongData: A, willPongTimestamp: Instant, createdTimestamp: Instant, executedTimestamp: Option[Instant], cancelledTimestamp: Option[Instant]) extends CborSerializable
  object StateName extends Enumeration {
    val Empty, Scheduled, Executed, Cancelled: Value = Value
  }

  def apply[A <: KafkaProducer.SerializableMessage](id: Id, quartzScheduler: ActorRef[QuartzAdapter.SchedulerActor.Command], kafkaProducer: KafkaProducer)(implicit akkaScheduler: Scheduler): Behavior[Message] =
    Behaviors.setup { implicit context =>
      EmptyPingJob[A](id, quartzScheduler, kafkaProducer)
    }
}
