package scheduler.pingJob.states

import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Scheduler}
import akka.persistence.typed.scaladsl.Effect
import akka.util.Timeout
import scheduler.KafkaProducer
import scheduler.pingJob.PingJob.{Id, Snapshot, StateName}
import scheduler.pingJob.PingJobApi.Message
import scheduler.pingJob.quartz.QuartzAdapter
import scheduler.pingJob.{PingJob, PingJobApi}

import java.time.Instant
import scala.util.{Failure, Success}

class EmptyPingJob[A <: KafkaProducer.SerializableMessage](id: Id, quartzScheduler: ActorRef[QuartzAdapter.SchedulerActor.Command], kafkaProducer: KafkaProducer, override val stateName: PingJob.StateName.Value = PingJob.StateName.Empty)(implicit akkaScheduler: Scheduler, context: ActorContext[Message], timeout: Timeout)
  extends PingJob.State {

  import PingJobApi._

  override def applyMessage(msg: Message): Effect[Event, PingJob.State] =
    msg match {
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
        Effect
          .none
      case Command.Schedule.QuartzDone(c) =>
        Effect
          .persist(Event.Scheduled(id, c.pongTopic, c.pongKey, c.pongData, c.willPongTimestamp, Instant.now()))
          .thenReply(c.replyTo)(_ => Command.Schedule.Result.Scheduled)
      case Command.Schedule.QuartzFailure(c, ex) =>
        Effect
          .reply(c.replyTo)(Command.Schedule.Result.Failure(ex))
      case m: Command.Execute =>
        Effect
          .reply(m.replyTo)(Command.Execute.Result.EmptyState)

      case m: Command.Cancel =>
        Effect
          .reply(m.replyTo)(Command.Cancel.Result.Cancelled)

      case m: Command.GetSnapshot =>
        Effect
          .reply(m.replyTo)(Command.GetSnapshot.Result.EmptyState)
    }

  override def applyEvent(state: PingJob.State, event: PingJobApi.Event): PingJob.State = event match {
    case e: Event.Scheduled[A] =>
      val snapshot = Snapshot(id, StateName.Scheduled, e.pongTopic, e.pongKey, e.pongData, e.willPongTimestamp, e.createdTimestamp, None, None)
      new ScheduledPingJob(id, quartzScheduler, kafkaProducer, snapshot)
  }
}
