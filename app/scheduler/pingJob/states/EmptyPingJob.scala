package scheduler.pingJob.states

import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior, Scheduler}
import akka.util.Timeout
import scheduler.KafkaProducer
import scheduler.pingJob.PingJob.{Id, Snapshot, StateName}
import scheduler.pingJob.PingJobApi
import scheduler.pingJob.PingJobApi.{Command, Message}
import scheduler.pingJob.quartz.QuartzAdapter

import java.time.Instant
import scala.util.{Failure, Success}

object EmptyPingJob {

  def apply[A <: KafkaProducer.SerializableMessage](id: Id, quartzScheduler: ActorRef[QuartzAdapter.SchedulerActor.Command], kafkaProducer: KafkaProducer)(implicit akkaScheduler: Scheduler, context: ActorContext[Message], timeout: Timeout): Behavior[PingJobApi.Message] =
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
        ScheduledPingJob(id, quartzScheduler, kafkaProducer, Snapshot(id, StateName.Scheduled, c.pongTopic, c.pongKey, c.pongData, c.willPongTimestamp, Instant.now(), None, None))
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
}
