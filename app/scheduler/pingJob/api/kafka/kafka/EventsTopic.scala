package scheduler.pingJob.api.kafka.kafka

import scheduler.pingJob.PingJob
import scheduler.{KafkaProducer, TopicKey, TopicName}

import java.time.Instant

object EventsTopic {

  val name: TopicName = TopicName(s"PingJob.events")

  sealed trait Message extends KafkaProducer.SerializableMessage {
    def ordering: BigInt
    def pingJobId: PingJob.Id
  }
  object Messages {
    case class Created[A <: KafkaProducer.SerializableMessage](ordering: BigInt, pingJobId: PingJob.Id, pongTopic: TopicName, pongKey: TopicKey, pongData: A, willPongTimestamp: Instant, createdTimestamp: Instant) extends Message
    case class Executed[A <: KafkaProducer.SerializableMessage](ordering: BigInt, pingJobId: PingJob.Id, pongTopic: TopicName, pongKey: TopicKey, pongData: A, createdTimestamp: Instant) extends Message
    case class Cancelled(ordering: BigInt, pingJobId: PingJob.Id, createdTimestamp: Instant) extends Message
    case class Deleted(ordering: BigInt, pingJobId: PingJob.Id) extends Message
  }
}
