package scheduler

import akka.actor.ActorSystem
import akka.actor.typed._
import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter.ClassicActorSystemOps
import akka.util.Timeout
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.annotation.JsonTypeIdResolver
import com.fasterxml.jackson.databind.jsontype.TypeSerializer
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.{JsonNode, JsonSerializer, ObjectMapper, SerializerProvider}
import com.typesafe.config.Config
import org.apache.kafka.clients.producer.{ProducerConfig, ProducerRecord, RecordMetadata, KafkaProducer => NativeKafkaProducer}
import org.apache.kafka.common.errors.SerializationException
import org.apache.kafka.common.serialization.{Deserializer, Serializer, StringSerializer}
import play.api.Logging

import java.time.Instant
import java.util.Properties
import javax.inject.{Inject, Singleton}
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

@Singleton
class KafkaProducer @Inject()(actorSystem: ActorSystem, idGenerator: IdGenerator, config: Config)(implicit ec: ExecutionContext, scheduler: Scheduler) extends Logging {

  import KafkaProducer._

  private val timeout: Timeout = Timeout(30.seconds)

  private val producer: NativeKafkaProducer[String, SerializableMessage] = new NativeKafkaProducer[String, SerializableMessage](getKafkaProducerProperties(config), new StringSerializer(), new KafkaMessageSerializer[SerializableMessage](idGenerator))
  private val producerWrapper: ActorRef[ActorWrapper.ActorMessage] = actorSystem
    .spawn(Behaviors.supervise(ActorWrapper(producer))
      .onFailure(SupervisorStrategy.restartWithBackoff(1.second, 10.seconds, 0.2)), "KafkaProducerWrapper")

  def sendMessage[A <: SerializableMessage](producerRecord: ProducerRecord[String, A]): Future[RecordMetadata] = {
    producerWrapper
      .ask[ActorWrapper.Commands.SendRecord.Result](replyTo => ActorWrapper.Commands.SendRecord(replyTo, producerRecord))(timeout, scheduler)
      .map {
        case r: ActorWrapper.Commands.SendRecord.Results.RecordSent => r.metadata
        case r: ActorWrapper.Commands.SendRecord.Results.SentFailed => throw r.exception
      }
      .andThen{
        case Success(v) => logger.debug(s"KafkaProducer: Message[topic=${producerRecord.topic()}, key=${producerRecord.key()}] sent. Returning RecordMetadata[partition=${v.partition()}, offset=${v.offset()}]")
        case Failure(e) => logger.error(s"KafkaProducer: sending Message[topic=${producerRecord.topic()}, key=${producerRecord.key()}] fails.", e)
      }
  }
}

object KafkaProducer {

  case class MessageId(value: String) extends AnyVal

  @JsonTypeInfo(use = JsonTypeInfo.Id.CUSTOM, include = JsonTypeInfo.As.PROPERTY, property = "_type")
  @JsonTypeIdResolver(classOf[TypeIdJacksonResolver])
  trait SerializableMessage
  case class KafkaEnvelope[A <: SerializableMessage](messageId : MessageId, message: A, messageTimestamp: Instant)
  case class PureJson(json: JsonNode) extends SerializableMessage

  private[KafkaProducer] def getKafkaProducerProperties(config: Config) : Properties = {
    val properties = new Properties()
    properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.getString("scheduler.kafka.bootstrapServers"))
    properties.put(ProducerConfig.RETRIES_CONFIG, 0) // overwrites default value of "retries" config: 2147483647
    properties.put(ProducerConfig.ACKS_CONFIG, "all") // equals to default value of "acks" config
    properties
  }

  private val objectMapper: ObjectMapper = scheduler.objectMapper.copy()
  private val kafkaModule = new SimpleModule("scheduler.KafkaProducerSerializers")
  kafkaModule.addSerializer(classOf[PureJson], new KafkaMessageSerializer.PureJsonSerializer())
  objectMapper.registerModule(kafkaModule)

  private[KafkaProducer] object ActorWrapper extends Logging {

    sealed trait ActorMessage
    sealed trait Command extends ActorMessage

    object Commands {
      case class SendRecord[A <: SerializableMessage](replyTo: ActorRef[SendRecord.Result], record: ProducerRecord[String, A]) extends Command
      object SendRecord {
        sealed trait Result
        object Results {
          case class RecordSent(metadata: RecordMetadata) extends Result
          case class SentFailed(exception: Exception) extends Result
        }
      }
    }

    import Commands._

    def apply[A <: SerializableMessage](producer: NativeKafkaProducer[String, A]): Behavior[ActorMessage] = Behaviors.setup(implicit context =>
      Behaviors.receiveMessage[ActorMessage] {
        case c: SendRecord[A] =>
          producer.send(c.record, (metadata: RecordMetadata, exception: Exception) =>
            if (exception == null) {
              logger.debug(s"ActorWrapper[NativeKafkaProducer]: Record[topic=${c.record.topic()}, key=${c.record.key()}] sent with RecordMetadata[partition=${metadata.partition()}, offset=${metadata.offset()}]")
              c.replyTo ! SendRecord.Results.RecordSent(metadata)
            } else {
              logger.error(s"ActorWrapper[NativeKafkaProducer]: Record[topic=${c.record.topic()}, key=${c.record.key()}]: sending fails", exception)
              c.replyTo ! SendRecord.Results.SentFailed(exception)
            }
          )
          Behaviors.same
        case c =>
          context.log.error(s"ActorWrapper[NativeKafkaProducer] receives UNEXPECTED Command[{}]. ActorWrapper will ignore this Command.", c, new RuntimeException(s"ActorWrapper[NativeKafkaProducer] receives UNEXPECTED Command[$c]"))
          Behaviors.same
      }
        .receiveSignal{
          case (_, PostStop) =>
            Try {
              context.log.debug(s"ActorWrapper[NativeKafkaProducer] receives PostStop signal, stopping NativeKafkaProducer")
              producer.flush()
              producer.close()
            } match {
              case Success(_) =>
                context.log.info(s"ActorWrapper[NativeKafkaProducer] handled PostStop signal, NativeKafkaProducer is closed")
                Behaviors.same
              case Failure(e) =>
                context.log.error(s"ActorWrapper[NativeKafkaProducer]: handling PostStop signal fails, NativeKafkaProducer can be still open.", e)
                Behaviors.stopped
            }
        }
    )
  }


  private class KafkaMessageSerializer[A <: SerializableMessage](idGenerator: IdGenerator) extends Serializer[A] with Logging {
    override def serialize(topic: String, data: A): Array[Byte] = {
      Try {
        data match {
          case null =>
            null
          case d: PureJson =>
            objectMapper.writeValueAsBytes(d)
          case d =>
            objectMapper.writeValueAsBytes(KafkaEnvelope(MessageId(idGenerator.nextId()), d, Instant.now))
        }
      } match {
        case Success(v) => v
        case Failure(e) =>
          logger.error(s"KafkaMessageSerializer fails for [topic=$topic, data=$data]", e)
          throw new SerializationException(e)
      }
    }
  }

  private object KafkaMessageSerializer {
    /*
    Information about PureJson class is loss, it serialize only value of `json` field. Impossible is deserialize it back to PureJson class.
    */
    class PureJsonSerializer extends JsonSerializer[PureJson] {
      override def serialize(value: PureJson, gen: JsonGenerator, serializers: SerializerProvider): Unit = {
        serializers.defaultSerializeValue(value.json, gen)
      }

      override def serializeWithType(value: PureJson, gen: JsonGenerator, serializers: SerializerProvider, typeSer: TypeSerializer): Unit = {
        serializers.defaultSerializeValue(value.json, gen)
      }
    }
  }

  object Deserializers {

    class KafkaEnvelopeDeserializer[A <: KafkaProducer.SerializableMessage] extends Deserializer[KafkaProducer.KafkaEnvelope[A]] with Logging {
      override def deserialize(topic: String, data: Array[Byte]): KafkaProducer.KafkaEnvelope[A] = {
        Try {
          data match {
            case null =>
              null
            case d =>
              objectMapper.readValue(d, classOf[KafkaProducer.KafkaEnvelope[A]])
          }
        } match {
          case Success(v) => v
          case Failure(e) =>
            logger.error(s"KafkaEnvelopeDeserializer fails for [topic=$topic, data=${data.mkString("Array(", ", ", ")")}]", e)
            throw new SerializationException(e)
        }
      }
    }
  }
}
