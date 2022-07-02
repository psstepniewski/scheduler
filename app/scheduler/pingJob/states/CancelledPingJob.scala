package scheduler.pingJob.states

import akka.actor.typed.Scheduler
import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.{Effect, ReplyEffect}
import akka.util.Timeout
import scheduler.KafkaProducer
import scheduler.pingJob.PingJob.Snapshot
import scheduler.pingJob.PingJobApi.{Command, Message}
import scheduler.pingJob.{PingJob, PingJobApi}

class CancelledPingJob[A <: KafkaProducer.SerializableMessage](snapshot: Snapshot[A], override val stateName: PingJob.StateName.Value = PingJob.StateName.Cancelled)(implicit akkaScheduler: Scheduler, context: ActorContext[Message], timeout: Timeout)
 extends PingJob.State {

  override def applyMessage(msg: Message): ReplyEffect[PingJobApi.Event, PingJob.State] = msg match {
    case m: Command.Schedule[_] =>
      Effect
        .reply(m.replyTo)(Command.Schedule.Result.CancelledState)

    case m: Command.Execute =>
      Effect
        .reply(m.replyTo)(Command.Execute.Result.CancelledState)

    case m: Command.Cancel =>
      Effect
        .reply(m.replyTo)(Command.Cancel.Result.AlreadyCancelled)

    case m: Command.GetSnapshot =>
      Effect
        .reply(m.replyTo)(Command.GetSnapshot.Result.Snapshot(snapshot))
  }

  override def applyEvent(state: PingJob.State, event: PingJobApi.Event): PingJob.State = event match {
    case _ =>
      //do nothing
      state
  }
}
