package scheduler.pingJob

import akka.actor.ActorSystem
import akka.actor.typed.{ActorRef, Scheduler}
import scheduler.KafkaProducer
import scheduler.pingJob.quartz.QuartzAdapter

import javax.inject.{Inject, Singleton}

@Singleton
class PingJobSelector @Inject()(actorSystem: ActorSystem, quartzAdapter: QuartzAdapter, kafkaProducer: KafkaProducer)(implicit akkaScheduler: Scheduler) {

  def actorRef(pingJobId: PingJob.Id): ActorRef[PingJobApi.Message] = ???
}
