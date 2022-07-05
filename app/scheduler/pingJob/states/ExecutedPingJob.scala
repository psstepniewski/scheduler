package scheduler.pingJob.states

import akka.actor.typed.scaladsl.{ActorContext, LoggerOps}
import akka.actor.typed.{ActorRef, Scheduler}
import akka.persistence.typed.scaladsl.Effect
import scheduler.KafkaProducer
import scheduler.pingJob.PingJob.{Id, Snapshot, StateName}
import scheduler.pingJob.PingJobApi.{Command, Message}
import scheduler.pingJob.quartz.QuartzAdapter
import scheduler.pingJob.{PingJob, PingJobApi}

class ExecutedPingJob[A <: KafkaProducer.SerializableMessage](id: Id, quartzScheduler: ActorRef[QuartzAdapter.SchedulerActor.Command], kafkaProducer: KafkaProducer, override val snapshot: Snapshot[A], override val stateName: StateName.Value = StateName.Executed)(implicit akkaScheduler: Scheduler, context: ActorContext[PingJobApi.Message])
  extends PingJob.State[A] {

  override def applyMessage(msg: Message): Effect[PingJobApi.Event, PingJob.State[A]] = msg match {
    case m: Command.Schedule[A] =>
      Effect
        .reply(m.replyTo)(Command.Schedule.Result.ExecutedState)

    case m: Command.Execute =>
      Effect
        .reply(m.replyTo)(Command.Execute.Result.AlreadyExecuted)

    case m: Command.GetSnapshot =>
      Effect
        .reply(m.replyTo)(Command.GetSnapshot.Result.Snapshot(snapshot))
  }

  override def applyEvent(state: PingJob.State[A], event: PingJobApi.Event): PingJob.State[A] = event match {
    case e =>
      context.log.warnN("PingJob[{}, {}] received unexpected Event[{}]", id, stateName, e)
      state
  }
}
