package scheduler.pingJob

import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior, Scheduler}
import akka.util.Timeout
import org.apache.kafka.clients.producer.ProducerRecord
import scheduler.pingJob.quartz.QuartzAdapter
import scheduler.{CborSerializable, KafkaProducer, TopicKey, TopicName}

import java.time.Instant
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}

object PingJob {

  import PingJobApi._

  private implicit val actorAskTimeout: Timeout = 30.seconds

  case class Id(value: String) extends AnyVal

  case class Snapshot[A <: KafkaProducer.SerializableMessage](id: Id, stateName: StateName.Value, pongTopic: TopicName, pongKey: TopicKey, pongData: A, willPongTimestamp: Instant, createdTimestamp: Instant, executedTimestamp: Option[Instant], cancelledTimestamp: Option[Instant]) extends CborSerializable
  object StateName extends Enumeration {
    val Empty, Scheduled, Executed, Cancelled: Value = Value
  }

  def apply[A <: KafkaProducer.SerializableMessage](id: Id, quartzScheduler: ActorRef[QuartzAdapter.SchedulerActor.Command], kafkaProducer: KafkaProducer)(implicit akkaScheduler: Scheduler): Behavior[Message] =
    Behaviors.setup { implicit context =>
      empty(id, quartzScheduler, kafkaProducer)
    }

  def empty[A <: KafkaProducer.SerializableMessage](id: Id, quartzScheduler: ActorRef[QuartzAdapter.SchedulerActor.Command], kafkaProducer: KafkaProducer)(implicit akkaScheduler: Scheduler, context: ActorContext[Message]): Behavior[Message] =
    Behaviors.receiveMessage {
      case m: Command.Schedule[A] =>
        val f = quartzScheduler
          .ask(replyTo => QuartzAdapter.SchedulerActor.Command.SchedulePingJob(replyTo, id, m.willPongTimestamp))
        context.pipeToSelf(f) {
          case Success(QuartzAdapter.SchedulerActor.Command.SchedulePingJob.Result.Scheduled) =>
            Command.Schedule.QuartzDone(m)
          case Success(QuartzAdapter.SchedulerActor.Command.SchedulePingJob.Result.AlreadyScheduled) =>
            Command.Schedule.QuartzDone(m)
          case Success(QuartzAdapter.SchedulerActor.Command.SchedulePingJob.Result.Failure(ex)) =>
            Command.Schedule.QuartzFailure(m, ex)
          case Failure(ex) =>
            Command.Schedule.QuartzFailure(m, ex)
        }
        Behaviors.same
      case Command.Schedule.QuartzDone(c) =>
        c.replyTo ! Command.Schedule.Result.Scheduled
        scheduled(id, quartzScheduler, kafkaProducer, Snapshot(id, StateName.Scheduled, c.pongTopic, c.pongKey, c.pongData, c.willPongTimestamp, Instant.now(), None, None))
      case Command.Schedule.QuartzFailure(c, ex) =>
        c.replyTo ! Command.Schedule.Result.Failure(ex)
        Behaviors.stopped

      case m: Command.Execute =>
        m.replyTo ! Command.Execute.Result.EmptyState
        Behaviors.same

      case m: Command.Cancel =>
        m.replyTo ! Command.Cancel.Result.Cancelled
        Behaviors.same

      case m: Command.GetSnapshot =>
        m.replyTo ! Command.GetSnapshot.Result.EmptyState
        Behaviors.same
    }


  def scheduled[A <: KafkaProducer.SerializableMessage](id: Id, quartzScheduler: ActorRef[QuartzAdapter.SchedulerActor.Command], kafkaProducer: KafkaProducer, snapshot: Snapshot[A])(implicit akkaScheduler: Scheduler, context: ActorContext[Message]): Behavior[Message] =
    Behaviors.receiveMessage{
      case m: Command.Schedule[_] =>
        m.replyTo ! Command.Schedule.Result.AlreadyScheduled
        Behaviors.same

      case m: Command.Execute =>
        val f = kafkaProducer
          .sendMessage(new ProducerRecord(
            snapshot.pongTopic.value,
            snapshot.pongKey.value,
            snapshot.pongData
          ))
        context.pipeToSelf(f) {
          case Success(_) =>
            Command.Execute.KafkaDone(m)
          case Failure(ex) =>
            Command.Execute.KafkaFailure(m, ex)
        }
        Behaviors.same
      case Command.Execute.KafkaDone(c) =>
        c.replyTo ! Command.Execute.Result.Executed
        executed(snapshot.copy(stateName = StateName.Executed, executedTimestamp = Some(Instant.now())))
      case Command.Execute.KafkaFailure(c, ex) =>
        c.replyTo ! Command.Execute.Result.Failure(ex)
        Behaviors.same

      case m: Command.Cancel =>
        quartzScheduler
          .ask(replyTo => QuartzAdapter.SchedulerActor.Command.DeletePingJob(replyTo, id))
        m.replyTo ! Command.Cancel.Result.AlreadyCancelled
        cancelled(snapshot.copy(stateName = StateName.Cancelled, cancelledTimestamp = Some(Instant.now())))

      case m: Command.GetSnapshot =>
        m.replyTo ! Command.GetSnapshot.Result.Snapshot(snapshot)
        Behaviors.same
    }

  def executed[A <: KafkaProducer.SerializableMessage](snapshot: Snapshot[A])(implicit akkaScheduler: Scheduler, context: ActorContext[Message]): Behavior[Message] =
    Behaviors.receiveMessage{
      case m: Command.Schedule[_] =>
        m.replyTo ! Command.Schedule.Result.ExecutedState
        Behaviors.same

      case m: Command.Execute =>
        m.replyTo ! Command.Execute.Result.AlreadyExecuted
        Behaviors.same

      case m: Command.Cancel =>
        m.replyTo ! Command.Cancel.Result.ExecutedState
        Behaviors.same

      case m: Command.GetSnapshot =>
        m.replyTo ! Command.GetSnapshot.Result.Snapshot(snapshot)
        Behaviors.same
    }


  def cancelled[A <: KafkaProducer.SerializableMessage](snapshot: Snapshot[A])(implicit akkaScheduler: Scheduler, context: ActorContext[Message]): Behavior[Message] =
    Behaviors.receiveMessage{
      case m: Command.Schedule[_] =>
        m.replyTo ! Command.Schedule.Result.CancelledState
        Behaviors.same

      case m: Command.Execute =>
        m.replyTo ! Command.Execute.Result.CancelledState
        Behaviors.same

      case m: Command.Cancel =>
        m.replyTo ! Command.Cancel.Result.AlreadyCancelled
        Behaviors.same

      case m: Command.GetSnapshot =>
        m.replyTo ! Command.GetSnapshot.Result.Snapshot(snapshot)
        Behaviors.same
    }
}
