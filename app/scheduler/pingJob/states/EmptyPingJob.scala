package scheduler.pingJob.states

import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior, Scheduler}
import scheduler.KafkaProducer
import scheduler.pingJob.PingJob.{Id, Snapshot, StateName}
import scheduler.pingJob.PingJobApi.{Command, Message}
import scheduler.pingJob.quartz.QuartzAdapter
import scheduler.actorAskTimeout

import java.time.Instant
import scala.util.{Failure, Success}

class EmptyPingJob[A <: KafkaProducer.SerializableMessage](id: Id, quartzScheduler: ActorRef[QuartzAdapter.SchedulerActor.Command], kafkaProducer: KafkaProducer)(implicit akkaScheduler: Scheduler) {

  def behavior(): Behavior[Message] = {
    Behaviors.setup { implicit context =>
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
        case m: Command.Schedule.QuartzDone[A] =>
          val c = m.c
          c.replyTo ! Command.Schedule.Result.Scheduled
          val snapshot = Snapshot[A](id, StateName.Scheduled, c.pongTopic, c.pongKey, c.pongData, c.willPongTimestamp, Instant.now(), None)
          new ScheduledPingJob(id, quartzScheduler, kafkaProducer, snapshot).behavior()
        case Command.Schedule.QuartzFailure(c, ex) =>
          c.replyTo ! Command.Schedule.Result.Failure(ex)
          Behaviors.same

        case m: Command.Execute =>
          m.replyTo ! Command.Execute.Result.EmptyState
          Behaviors.same

        case m: Command.GetSnapshot =>
          m.replyTo ! Command.GetSnapshot.Result.EmptyState
          Behaviors.same
      }
    }
  }
}
