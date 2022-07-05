package scheduler.pingJob.states

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior, Scheduler}
import org.apache.kafka.clients.producer.ProducerRecord
import scheduler.KafkaProducer
import scheduler.pingJob.PingJob.{Id, Snapshot, StateName}
import scheduler.pingJob.PingJobApi.{Command, Message}
import scheduler.pingJob.quartz.QuartzAdapter

import java.time.Instant
import scala.util.{Failure, Success}

class ScheduledPingJob[A <: KafkaProducer.SerializableMessage](id: Id, quartzScheduler: ActorRef[QuartzAdapter.SchedulerActor.Command], kafkaProducer: KafkaProducer, snapshot: Snapshot[A])(implicit akkaScheduler: Scheduler) {

  def behavior(): Behavior[Message] =
    Behaviors.setup(implicit context => {
      Behaviors.receiveMessage {
        case m: Command.Schedule[A] =>
          m.replyTo ! Command.Schedule.Result.AlreadyScheduled
          Behaviors.same

        case m: Command.Execute =>
          val f =  kafkaProducer
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
          new ExecutedPingJob(id, quartzScheduler, kafkaProducer, snapshot.copy(stateName = StateName.Executed, executedTimestamp = Some(Instant.now()))).behavior()
        case Command.Execute.KafkaFailure(c, ex) =>
          c.replyTo ! Command.Execute.Result.Failure(ex)
          Behaviors.same

        case m: Command.GetSnapshot =>
          m.replyTo ! Command.GetSnapshot.Result.Snapshot(snapshot)
          Behaviors.same
      }
    })
}
