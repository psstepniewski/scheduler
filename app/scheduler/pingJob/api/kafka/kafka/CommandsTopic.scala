package scheduler.pingJob.api.kafka.kafka

import scheduler.pingJob.PingJob
import scheduler.{KafkaProducer, TopicKey, TopicName}

import java.time.Instant

object CommandsTopic {

  val name: TopicName = TopicName(s"PingJob.commands")

  sealed trait Message extends KafkaProducer.SerializableMessage
  object Messages {
    case class Create[A <: KafkaProducer.SerializableMessage](pongTopic: TopicName, pongKey: TopicKey, pongData: A, willPongTimestamp: Instant) extends Message
    case class Cancel(pingJobId: PingJob.Id) extends Message
    case class Execute(pingJobId: PingJob.Id) extends Message
  }
}
