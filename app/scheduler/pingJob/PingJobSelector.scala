package scheduler.pingJob

import akka.actor.ActorSystem
import akka.actor.typed.scaladsl.adapter.ClassicActorSystemOps
import akka.actor.typed.{ActorRef, Scheduler}
import scheduler.KafkaProducer
import scheduler.pingJob.quartz.QuartzAdapter

import javax.inject.{Inject, Singleton}
import scala.collection.mutable

@Singleton
class PingJobSelector @Inject()(actorSystem: ActorSystem, quartzAdapter: QuartzAdapter, kafkaProducer: KafkaProducer)(implicit akkaScheduler: Scheduler) {

  private val actors: mutable.Map[PingJob.Id, ActorRef[PingJobApi.Message]] = mutable.Map()

  def actorRef(pingJobId: PingJob.Id): ActorRef[PingJobApi.Message] = {
    actors.getOrElse(pingJobId, {
      val ref = actorSystem.spawn(PingJob(pingJobId, quartzAdapter.scheduler, kafkaProducer), pingJobId.value)
      actors += (pingJobId -> ref)
      ref
    })
  }
}
