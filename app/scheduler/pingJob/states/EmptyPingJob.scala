package scheduler.pingJob.states

import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.scaladsl.{ActorContext, LoggerOps}
import akka.actor.typed.{ActorRef, Scheduler}
import akka.persistence.typed.scaladsl.Effect
import scheduler.pingJob.PingJob.{Id, Snapshot, StateName}
import scheduler.pingJob.PingJobApi.{Command, Event, Message}
import scheduler.pingJob.quartz.QuartzAdapter
import scheduler.pingJob.{PingJob, PingJobApi}
import scheduler.{KafkaProducer, actorAskTimeout}

import java.time.Instant
import scala.util.{Failure, Success}

class EmptyPingJob[A <: KafkaProducer.SerializableMessage](id: Id, quartzScheduler: ActorRef[QuartzAdapter.SchedulerActor.Command], kafkaProducer: KafkaProducer, override val stateName: StateName.Value = StateName.Empty)(implicit akkaScheduler: Scheduler, context: ActorContext[PingJobApi.Message])
  extends PingJob.State[A] {

  override def snapshot: Snapshot[A] = throw new UnsupportedOperationException(s"PingJob[$id, $stateName] is Empty")

  override def applyMessage(msg: Message): Effect[PingJobApi.Event, PingJob.State[A]] = msg match {
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
    case m: Command.Schedule.QuartzDone[A] =>
      val c = m.c
      Effect
        .persist(Event.Scheduled(id, c.pongTopic, c.pongKey, c.pongData, c.willPongTimestamp, Instant.now()))
        .thenReply(c.replyTo)(_ => Command.Schedule.Result.Scheduled)
    case Command.Schedule.QuartzFailure(c, ex) =>
      Effect
        .reply(c.replyTo)(Command.Schedule.Result.Failure(ex))

    case m: Command.Execute =>
      Effect
        .reply(m.replyTo)(Command.Execute.Result.EmptyState)

    case m: Command.GetSnapshot =>
      Effect
        .reply(m.replyTo)(Command.GetSnapshot.Result.EmptyState)
  }

  override def applyEvent(state: PingJob.State[A], event: PingJobApi.Event): PingJob.State[A] = event match {
    case e: Event.Scheduled[A] =>
      val snapshot = Snapshot(id, StateName.Scheduled, e.pongTopic, e.pongKey, e.pongData, e.willPongTimestamp, e.eventTimestamp, None)
      new ScheduledPingJob[A](id, quartzScheduler, kafkaProducer, snapshot)
    case e =>
      context.log.warnN("PingJob[{}, {}] received unexpected Event[{}]", id, stateName, e)
      state
  }
}
