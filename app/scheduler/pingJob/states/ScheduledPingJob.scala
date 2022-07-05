package scheduler.pingJob.states

import akka.actor.typed.scaladsl.{ActorContext, LoggerOps}
import akka.actor.typed.{ActorRef, Scheduler}
import akka.persistence.typed.scaladsl.Effect
import org.apache.kafka.clients.producer.ProducerRecord
import scheduler.KafkaProducer
import scheduler.pingJob.PingJob.{Id, Snapshot, StateName}
import scheduler.pingJob.PingJobApi.{Command, Event, Message}
import scheduler.pingJob.quartz.QuartzAdapter
import scheduler.pingJob.{PingJob, PingJobApi}

import java.time.Instant
import scala.util.{Failure, Success}

class ScheduledPingJob[A <: KafkaProducer.SerializableMessage](id: Id, quartzScheduler: ActorRef[QuartzAdapter.SchedulerActor.Command], kafkaProducer: KafkaProducer, override val snapshot: Snapshot[A], override val stateName: StateName.Value = StateName.Scheduled)(implicit akkaScheduler: Scheduler, context: ActorContext[PingJobApi.Message])
  extends PingJob.State[A] {

  override def applyMessage(msg: Message): Effect[PingJobApi.Event, PingJob.State[A]] = msg match {
    case m: Command.Schedule[A] =>
      Effect
        .reply(m.replyTo)(Command.Schedule.Result.AlreadyScheduled)

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
      Effect
        .none
    case Command.Execute.KafkaDone(c) =>
      Effect
        .persist(Event.Executed(id, snapshot.pongTopic, snapshot.pongKey, snapshot.pongData, snapshot.willPongTimestamp, Instant.now()))
        .thenReply(c.replyTo)(_ => Command.Execute.Result.Executed)
    case Command.Execute.KafkaFailure(c, ex) =>
      Effect
        .reply(c.replyTo)(Command.Execute.Result.Failure(ex))

    case m: Command.GetSnapshot =>
      Effect
        .reply(m.replyTo)(Command.GetSnapshot.Result.Snapshot(snapshot))
  }

  override def applyEvent(state: PingJob.State[A], event: PingJobApi.Event): PingJob.State[A] = event match {
    case e: Event.Executed[A] =>
      new ExecutedPingJob[A](id, quartzScheduler, kafkaProducer, snapshot.copy(stateName = StateName.Executed, executedTimestamp = Some(e.eventTimestamp)))
    case e =>
      context.log.warnN("PingJob[{}, {}] received unexpected Event[{}]", id, stateName, e)
      state
  }
}
