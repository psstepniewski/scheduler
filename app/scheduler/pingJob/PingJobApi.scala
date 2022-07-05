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
        case object CancelledState extends Result
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
    private[pingJob] case class GetSnapshot(replyTo: ActorRef[GetSnapshot.Result]) extends Command
    private[pingJob] object GetSnapshot {
      sealed trait Result
      object Result {
        case class Snapshot(value: PingJob.Snapshot[_ <: KafkaProducer.SerializableMessage]) extends Result
        case object EmptyState extends Result
      }
    }
  }
}
