package scheduler.pingJob

import akka.actor.typed._
import akka.actor.typed.scaladsl.{Behaviors, LoggerOps}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import scheduler.pingJob.quartz.QuartzAdapter
import scheduler.pingJob.states.EmptyPingJob
import scheduler.{CborSerializable, KafkaProducer}

import java.time.Instant

object PingJob {

  import PingJobApi._

  private val entityName: String = "PingJob"

  case class Id(value: String) extends AnyVal
  case class TopicName(value: String) extends AnyVal
  case class TopicKey(value: String) extends AnyVal

  private[pingJob] trait State[A <: KafkaProducer.SerializableMessage] {
    def snapshot: Snapshot[A]
    def applyMessage(msg: Message): Effect[Event, State[A]]
    def applyEvent(state: State[A], event: Event): State[A]
    def stateName: StateName.Value
  }
  case class Snapshot[A <: KafkaProducer.SerializableMessage](id: Id, stateName: StateName.Value, pongTopic: TopicName, pongKey: TopicKey, pongData: A, willPongTimestamp: Instant, createdTimestamp: Instant, executedTimestamp: Option[Instant]) extends CborSerializable
  object StateName extends Enumeration {
    val Empty, Scheduled, Executed: Value = Value
  }

  def apply[A <: KafkaProducer.SerializableMessage](id: Id, quartzScheduler: ActorRef[QuartzAdapter.SchedulerActor.Command], kafkaProducer: KafkaProducer)(implicit akkaScheduler: Scheduler): Behavior[Message] =
    Behaviors.setup { implicit context =>
      context.log.debug2("Starting entity actor {}[{}]", entityName, id)
      EventSourcedBehavior[Message, Event, State[A]](
        persistenceId(id),
        new EmptyPingJob[A](id, quartzScheduler, kafkaProducer),
        (state, msg) => {
          context.log.debug("{}[{}, {}] receives message {}", entityName, id, state.stateName, msg)
          state.applyMessage(msg)
        },
        (state, event) => {
          context.log.debug("{}[{}, {}] applies event {}", entityName, id, state.stateName, event)
          state.applyEvent(state, event)
        }
      )
      .withTagger(_ => Set(scheduler.serviceName, entityName))
      .receiveSignal {
        case (state, PreRestart) => context.log.debugN("{}[{}, {}] receives PreRestart signal", entityName, id, state.stateName)
        case (state, PostStop) => context.log.debugN("{}[{}, {}] receives PostStop signal", entityName, id, state.stateName)
      }
    }

  def persistenceId(id: Id): PersistenceId = PersistenceId.of(entityName, id.value)
}
