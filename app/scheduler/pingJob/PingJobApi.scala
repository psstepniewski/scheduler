package scheduler.pingJob

import akka.actor.typed.ActorRef
import scheduler._

import java.time.Instant

object PingJobApi {

  trait Message extends CborSerializable
  protected trait Command extends Message

  object Command {
    case class Create[A <: KafkaProducer.SerializableMessage](replyTo: ActorRef[Create.Result], pongTopic: TopicName, pongKey: TopicKey, pongData: A, willPongTimestamp: Instant) extends Command
    object Create {
      sealed trait Result
      object Result {
        case object Created extends Result
        case object AlreadyCreated extends Result
        case class Failure(ex: Throwable) extends Result
      }
    }
    case class Execute(replyTo: ActorRef[Execute.Result]) extends Command
    object Execute {
      sealed trait Result
      object Result {
        case object Executed extends Result
        case object AlreadyExecuted extends Result
        case object EmptyState extends Result
        case object CancelledState extends Result
        case class Failure(ex: Throwable) extends Result
      }
    }
    case class Cancel(replyTo: ActorRef[Cancel.Result]) extends Command
    object Cancel {
      sealed trait Result
      object Result {
        case object Cancelled extends Result
        case object AlreadyCancelled extends Result
        case object EmptyState extends Result
        case object ExecutedState extends Result
        case class Failure(ex: Throwable) extends Result
      }
    }
  }

  sealed trait Event extends CborSerializable
  object Event {
    case class Created[A <: KafkaProducer.SerializableMessage](pingJobId: PingJob.Id, pongTopic: TopicName, pongKey: TopicKey, pongData: A, willPongTimestamp: Instant, createdTimestamp: Instant) extends Event
    case class Executed[A <: KafkaProducer.SerializableMessage](pingJobId: PingJob.Id, pongTopic: TopicName, pongKey: TopicKey, pongData: A, createdTimestamp: Instant) extends Event
    case class Cancelled(pingJobId: PingJob.Id, createdTimestamp: Instant) extends Event
    case class Deleted(pingJobId: PingJob.Id, createdTimestamp: Instant) extends Event
  }
}
