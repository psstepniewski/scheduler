package scheduler.pingJob

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior, Scheduler}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import scheduler.pingJob.quartz.QuartzAdapter
import scheduler.pingJob.states.EmptyPingJob
import scheduler.{CborSerializable, KafkaProducer, actorAskTimeout}

import java.time.Instant

object PingJob {

  import PingJobApi._

  private val entityName = "PingJob"

  case class Id(value: String) extends AnyVal
  case class TopicName(value: String) extends AnyVal
  case class TopicKey(value: String) extends AnyVal

  case class Snapshot[A <: KafkaProducer.SerializableMessage](id: Id, stateName: StateName.Value, pongTopic: TopicName, pongKey: TopicKey, pongData: A, willPongTimestamp: Instant, createdTimestamp: Instant, executedTimestamp: Option[Instant], cancelledTimestamp: Option[Instant]) extends CborSerializable
  object StateName extends Enumeration {
    val Empty, Scheduled, Executed, Cancelled: Value = Value
  }
  trait State {
    def applyMessage(msg: Message): Effect[Event, State]
    def applyEvent(state: State, event: Event): State
    def stateName: StateName.Value
  }

  def apply[A <: KafkaProducer.SerializableMessage](id: Id, quartzScheduler: ActorRef[QuartzAdapter.SchedulerActor.Command], kafkaProducer: KafkaProducer)(implicit akkaScheduler: Scheduler): Behavior[Message] =
    Behaviors.setup { implicit context =>
      EventSourcedBehavior[Message, Event, State](
        persistenceId = persistenceId(id),
        emptyState = new EmptyPingJob(id, quartzScheduler, kafkaProducer),
        commandHandler = (state, msg) => {
          context.log.debug("{}[{},{}] received message {}", entityName, id, state.stateName, msg)
          state.applyMessage(msg)
        },
        eventHandler = (state, event) => {
          context.log.debug("{}[{},{}] will apply event {}", entityName, id, state.stateName, event)
          state.applyEvent(state, event)
        }
      )
    }

  def persistenceId(id: Id): PersistenceId = PersistenceId.of(entityName, id.value)
}
