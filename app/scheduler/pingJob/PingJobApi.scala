package scheduler.pingJob

import akka.actor.typed.ActorRef
import scheduler._

import java.time.Instant

object PingJobApi {

  trait Message extends CborSerializable
  protected trait Command extends Message

  object Command {
    case class Schedule[A <: KafkaProducer.SerializableMessage](replyTo: ActorRef[Schedule.Result], pongTopic: PingJob.TopicName, pongKey: PingJob.TopicKey, pongData: A, willPongTimestamp: Instant) extends Command
    object Schedule {
      sealed trait Result
      object Result {
        case object Scheduled extends Result
        case object AlreadyScheduled extends Result
        case object ExecutedState extends Result
        case class Failure(ex: Throwable) extends Result
      }
      case class QuartzDone[A <: KafkaProducer.SerializableMessage](c: Schedule[A]) extends Message
      case class QuartzFailure[A <: KafkaProducer.SerializableMessage](c: Schedule[A], ex: Throwable) extends Message
    }
    case class Execute(replyTo: ActorRef[Execute.Result]) extends Command
    object Execute {
      sealed trait Result
      object Result {
        case object Executed extends Result
        case object AlreadyExecuted extends Result
        case object EmptyState extends Result
        case class Failure(ex: Throwable) extends Result
      }
      case class KafkaDone(c: Execute) extends Message
      case class KafkaFailure(c: Execute, ex: Throwable) extends Message
    }
    private[pingJob] case class GetSnapshot(replyTo: ActorRef[GetSnapshot.Result]) extends Command
    private[pingJob] object GetSnapshot {
      sealed trait Result
      object Result {
        case class Snapshot(value: PingJob.Snapshot[_ <: KafkaProducer.SerializableMessage]) extends Result
        case object EmptyState extends Result
      }
    }
  }

  sealed trait Event extends CborSerializable
  object Event {
    case class Scheduled[A <: KafkaProducer.SerializableMessage](pingJobId: PingJob.Id, pongTopic: PingJob.TopicName, pongKey: PingJob.TopicKey, pongData: A, willPongTimestamp: Instant, eventTimestamp: Instant) extends Event
    case class Executed[A <: KafkaProducer.SerializableMessage](pingJobId: PingJob.Id, pongTopic: PingJob.TopicName, pongKey: PingJob.TopicKey, pongData: A, willPongTimestamp: Instant, eventTimestamp: Instant) extends Event
  }
}
