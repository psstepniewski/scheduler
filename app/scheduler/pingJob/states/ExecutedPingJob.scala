package scheduler.pingJob.states

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior, Scheduler}
import akka.util.Timeout
import scheduler.KafkaProducer
import scheduler.pingJob.PingJob.{Id, Snapshot}
import scheduler.pingJob.PingJobApi
import scheduler.pingJob.PingJobApi.{Command, Message}
import scheduler.pingJob.quartz.QuartzAdapter

object ExecutedPingJob {

  def apply[A <: KafkaProducer.SerializableMessage](id: Id, quartzScheduler: ActorRef[QuartzAdapter.SchedulerActor.Command], kafkaProducer: KafkaProducer, snapshot: Snapshot[A])(implicit akkaScheduler: Scheduler, context: ActorContext[Message], timeout: Timeout): Behavior[PingJobApi.Message] =
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
}
