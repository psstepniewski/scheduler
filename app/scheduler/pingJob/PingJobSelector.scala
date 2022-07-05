package scheduler.pingJob

import akka.actor.ActorSystem
import akka.actor.typed.Scheduler
import akka.actor.typed.scaladsl.adapter.ClassicActorSystemOps
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityRef}
import scheduler.KafkaProducer
import scheduler.pingJob.quartz.QuartzAdapter

import javax.inject.{Inject, Singleton}

@Singleton
class PingJobSelector @Inject()(actorSystem: ActorSystem, quartzAdapter: QuartzAdapter, kafkaProducer: KafkaProducer)(implicit akkaScheduler: Scheduler) {

  private val sharding = ClusterSharding(actorSystem.toTyped)

  sharding.init(Entity(PingJob.TypeKey) { implicit entityContext =>
    PingJob(PingJob.Id(entityContext.entityId), quartzAdapter.scheduler, kafkaProducer)
  })

  def entityRef(pingJobId: PingJob.Id): EntityRef[PingJobApi.Message] = {
    sharding.entityRefFor(PingJob.TypeKey, pingJobId.value)
  }
}
