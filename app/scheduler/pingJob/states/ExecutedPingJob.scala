package scheduler.pingJob.states

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior, Scheduler}
import scheduler.KafkaProducer
import scheduler.pingJob.PingJob.{Id, Snapshot}
import scheduler.pingJob.PingJobApi.{Command, Message}
import scheduler.pingJob.quartz.QuartzAdapter

class ExecutedPingJob[A <: KafkaProducer.SerializableMessage](id: Id, quartzScheduler: ActorRef[QuartzAdapter.SchedulerActor.Command], kafkaProducer: KafkaProducer, snapshot: Snapshot[A])(implicit akkaScheduler: Scheduler) {

  def behavior(): Behavior[Message] =
    Behaviors.setup(implicit context => {
      Behaviors.receiveMessage{
        case m: Command.Schedule[A] =>
          m.replyTo ! Command.Schedule.Result.ExecutedState
          Behaviors.same

        case m: Command.Execute =>
          m.replyTo ! Command.Execute.Result.AlreadyExecuted
          Behaviors.same

        case m: Command.GetSnapshot =>
          m.replyTo ! Command.GetSnapshot.Result.Snapshot(snapshot)
          Behaviors.same
      }
    })
}
