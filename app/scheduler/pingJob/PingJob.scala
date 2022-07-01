package scheduler.pingJob

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.http.scaladsl.model.ws.Message
import scheduler.pingJob.quartz.QuartzAdapter
import scheduler.{CborSerializable, KafkaProducer, TopicKey, TopicName}

import java.time.Instant

object PingJob {

  val TypeKey: EntityTypeKey[Message] = EntityTypeKey[Message]("PingJob")

  case class Id(value: String) extends AnyVal

  case class Snapshot[A <: KafkaProducer.SerializableMessage](id: Id, stateName: StateName.Value, pongTopic: TopicName, pongKey: TopicKey, pongData: A, willPongTimestamp: Instant, executedTimestamp: Option[Instant], cancelledTimestamp: Option[Instant]) extends CborSerializable
  object StateName extends Enumeration {
    type StateName = Value
    val Empty, Scheduled, Executed, Cancelled: Value = Value
  }

  def apply[A <: KafkaProducer.SerializableMessage](id: Id, quartzScheduler: ActorRef[QuartzAdapter.SchedulerActor.Command], kafkaProducer: KafkaProducer): Behavior[Message] = Behaviors.setup { implicit context =>
    ???
  }
}
