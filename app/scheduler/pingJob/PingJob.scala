package scheduler.pingJob

import akka.actor.typed.{ActorRef, Behavior, Scheduler}
import scheduler.pingJob.quartz.QuartzAdapter
import scheduler.{CborSerializable, KafkaProducer}

import java.time.Instant

object PingJob {

  import PingJobApi._

  case class Id(value: String) extends AnyVal
  case class TopicName(value: String) extends AnyVal
  case class TopicKey(value: String) extends AnyVal

  case class Snapshot[A <: KafkaProducer.SerializableMessage](id: Id, stateName: StateName.Value, pongTopic: TopicName, pongKey: TopicKey, pongData: A, willPongTimestamp: Instant, createdTimestamp: Instant, executedTimestamp: Option[Instant]) extends CborSerializable
  object StateName extends Enumeration {
    val Empty, Scheduled, Executed: Value = Value
  }

  def apply[A <: KafkaProducer.SerializableMessage](id: Id, quartzScheduler: ActorRef[QuartzAdapter.SchedulerActor.Command], kafkaProducer: KafkaProducer)(implicit akkaScheduler: Scheduler): Behavior[Message] =
    ???
}
