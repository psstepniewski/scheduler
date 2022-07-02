package scheduler.pingJob.states

import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.{ActorRef, Scheduler}
import akka.persistence.typed.scaladsl.{Effect, ReplyEffect}
import akka.util.Timeout
import org.apache.kafka.clients.producer.ProducerRecord
import scheduler.KafkaProducer
import scheduler.pingJob.PingJob.{Id, Snapshot, StateName}
import scheduler.pingJob.PingJobApi.{Command, Event, Message}
import scheduler.pingJob.quartz.QuartzAdapter
import scheduler.pingJob.{PingJob, PingJobApi}

import java.time.Instant
import scala.util.{Failure, Success}

class ScheduledPingJob[A <: KafkaProducer.SerializableMessage](id: Id, quartzScheduler: ActorRef[QuartzAdapter.SchedulerActor.Command], kafkaProducer: KafkaProducer, snapshot: Snapshot[A], override val stateName: PingJob.StateName.Value = PingJob.StateName.Scheduled)(implicit akkaScheduler: Scheduler, context: ActorContext[Message], timeout: Timeout)
  extends PingJob.State {

  override def applyMessage(msg: Message): ReplyEffect[PingJobApi.Event, PingJob.State] = msg match {
    case m: Command.Schedule[_] =>
      Effect
        .reply(m.replyTo)(Command.Schedule.Result.AlreadyScheduled)

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
      Effect
        .noReply
    case Command.Execute.KafkaDone(c) =>
      Effect
        .persist(Event.Executed(id, snapshot.pongTopic, snapshot.pongKey, snapshot.pongData, Instant.now()))
        .thenReply(c.replyTo)(_ => Command.Execute.Result.Executed)
    case Command.Execute.KafkaFailure(c, ex) =>
      Effect
        .reply(c.replyTo)(Command.Execute.Result.Failure(ex))

    case m: Command.Cancel =>
      quartzScheduler
        .ask(replyTo => QuartzAdapter.SchedulerActor.Command.DeletePingJob(replyTo, id))
      Effect
        .persist(Event.Cancelled(id, Instant.now()))
        .thenReply(m.replyTo)(_ => Command.Cancel.Result.Cancelled)

    case m: Command.GetSnapshot =>
      Effect
        .reply(m.replyTo)(Command.GetSnapshot.Result.Snapshot(snapshot))
    }

  override def applyEvent(state: PingJob.State, event: PingJobApi.Event): PingJob.State = event match {
    case e: Event.Executed[A] =>
      new ExecutedPingJob(snapshot.copy(stateName = StateName.Executed, executedTimestamp = Some(e.createdTimestamp)))
    case e: Event.Cancelled =>
      new CancelledPingJob(snapshot.copy(stateName = StateName.Cancelled, cancelledTimestamp = Some(e.createdTimestamp)))
  }
}
