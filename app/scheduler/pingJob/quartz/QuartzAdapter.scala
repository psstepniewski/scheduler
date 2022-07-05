package scheduler.pingJob.quartz

import akka.actor.ActorSystem
import akka.actor.typed._
import akka.actor.typed.scaladsl.Behaviors.Receive
import akka.actor.typed.scaladsl.adapter.ClassicActorSystemOps
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, LoggerOps}
import akka.cluster.typed.{ClusterSingleton, SingletonActor}
import com.typesafe.config.Config
import org.quartz
import org.quartz.impl.StdSchedulerFactory
import org.quartz.spi.{JobFactory, TriggerFiredBundle}
import org.quartz.{Job, JobDataMap, ObjectAlreadyExistsException, Scheduler => NativeScheduler}
import play.api.Logging
import play.api.inject.Injector
import scheduler.CborSerializable
import scheduler.pingJob.PingJob
import scheduler.pingJob.quartz.QuartzAdapter.SchedulerActor.Command._
import scheduler.pingJob.quartz.QuartzAdapter.SchedulerActor.Message

import java.time.Instant
import java.util.Properties
import javax.inject.{Inject, Singleton}
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success, Try}

@Singleton
class QuartzAdapter @Inject()(actorSystem: ActorSystem, injector: Injector, config: Config) extends Logging {

  import QuartzAdapter._

  private val schedulerName: String = s"PingJobScheduler"
  private val schedulerId: String = s"PingJobScheduler_0"
  private val dataSourceName: String = "scheduler"
  private val threadCount: String = "3"
  private val maxConnections: String = "3"

  logger.debug(s"Starting PingJob QuartzAdapter[$schedulerId]")

  private val driver: String = config.getString("scheduler.db.driver")
  private val url: String = config.getString("scheduler.db.url")
  private val username: String = config.getString("scheduler.db.username")
  private val password: String = config.getString("scheduler.db.password")

  val quartzProps: Properties = new Properties()
  quartzProps.setProperty("org.quartz.scheduler.instanceName", schedulerName)
  quartzProps.setProperty("org.quartz.scheduler.instanceId", schedulerId)
  quartzProps.setProperty("org.quartz.threadPool.class", "org.quartz.simpl.SimpleThreadPool")
  quartzProps.setProperty("org.quartz.threadPool.threadCount", threadCount)
  quartzProps.setProperty("org.quartz.jobStore.useProperties", "true")
  quartzProps.setProperty("org.quartz.jobStore.class", "org.quartz.impl.jdbcjobstore.JobStoreTX")
  quartzProps.setProperty("org.quartz.jobStore.dataSource", dataSourceName)
  quartzProps.setProperty("org.quartz.jobStore.tablePrefix", "qrtz_")
  quartzProps.setProperty("org.quartz.jobStore.driverDelegateClass", "org.quartz.impl.jdbcjobstore.PostgreSQLDelegate")
  quartzProps.setProperty("org.quartz.scheduler.skipUpdateCheck", "true")
  quartzProps.setProperty(s"org.quartz.dataSource.$dataSourceName.driver",  driver)
  quartzProps.setProperty(s"org.quartz.dataSource.$dataSourceName.URL", url)
  quartzProps.setProperty(s"org.quartz.dataSource.$dataSourceName.user", username)
  quartzProps.setProperty(s"org.quartz.dataSource.$dataSourceName.password", password)
  quartzProps.setProperty(s"org.quartz.dataSource.$dataSourceName.maxConnections", maxConnections)

  private val jobFactory = new QuartzJobFactory(injector, schedulerId)

  val scheduler: ActorRef[SchedulerActor.Message] = ClusterSingleton(actorSystem.toTyped).init(SingletonActor(
    Behaviors.supervise(QuartzAdapter.SchedulerActor(jobFactory, quartzProps)).onFailure(SupervisorStrategy.restartWithBackoff(1.second, 10.seconds, 0.2)),
    schedulerId
  ))
}

object QuartzAdapter {

  class SchedulerActor(private val context: ActorContext[SchedulerActor.Message], private val nativeScheduler: quartz.Scheduler) {
    def idle(): Receive[Message] = Behaviors.receiveMessage[Message] {
      case c: SchedulePingJob =>
        Try {
          context.log.debug2("SchedulerActor receives SchedulePingJob[{}, willPongTimestamp={}] command", c.pingJobId, c.willPongTimestamp)
          QuartzPingJob.schedule(c.pingJobId, c.willPongTimestamp)(nativeScheduler)
        } match {
          case Success(_) =>
            c.replyTo ! SchedulePingJob.Result.Scheduled
          case Failure(_: ObjectAlreadyExistsException) =>
            context.log.debug2("SchedulerActor#SchedulePingJob[{}, willPongTimestamp={}] didn't find quartz job", c.pingJobId, c.willPongTimestamp)
            c.replyTo ! SchedulePingJob.Result.AlreadyScheduled
          case Failure(ex) =>
            c.replyTo ! SchedulePingJob.Result.Failure(ex)
        }
        Behaviors.same
      case c: TriggerPingJob =>
        Try {
          context.log.debug("SchedulerActor receives TriggerPingJob[{}] command", c.pingJobId)
          val jobKey = QuartzPingJob.jobKey(c.pingJobId)
          if(!nativeScheduler.checkExists(jobKey)) {
            context.log.debug("SchedulerActor#TriggerPingJob[{}] didn't find quartz job", c.pingJobId)
            c.replyTo ! TriggerPingJob.Result.NotFound
          }
          else {
            val jobMap = new JobDataMap()
            jobMap.putAsString(QuartzPingJob.RETRY_NO, -1)
            nativeScheduler.triggerJob(jobKey, jobMap)
            c.replyTo ! TriggerPingJob.Result.Triggered
          }
        } match {
          case Success(_) =>
            //do nothing, scheduler already replied to replyTo
          case Failure(ex) =>
            c.replyTo ! TriggerPingJob.Result.Failure(ex)
        }
        Behaviors.same
      case c: DeletePingJob =>
        Try {
          context.log.debug("SchedulerActor receives DeletePingJob[{}] command", c.pingJobId)
          val jobKey = QuartzPingJob.jobKey(c.pingJobId)
          if(!nativeScheduler.checkExists(jobKey)) {
            context.log.debug("SchedulerActor#DeletePingJob[{}] didn't find quartz job", c.pingJobId)
            c.replyTo ! DeletePingJob.Result.NotFound
          }
          else {
            nativeScheduler.deleteJob(QuartzPingJob.jobKey(c.pingJobId))
            c.replyTo ! DeletePingJob.Result.Deleted
          }
        } match {
          case Success(_) =>
            //do nothing, scheduler already replied to replyTo
          case Failure(ex) =>
            c.replyTo ! DeletePingJob.Result.Failure(ex)
        }
        Behaviors.same
      case c =>
        context.log.warn("SchedulerActor receives unexpected message: {}", c)
        Behaviors.same
    }
  }

  object SchedulerActor {

    sealed trait Message extends CborSerializable
    sealed trait Command extends Message

    object Command {
      case class SchedulePingJob(replyTo: ActorRef[SchedulePingJob.Result], pingJobId: PingJob.Id, willPongTimestamp: Instant) extends Command
      object SchedulePingJob {
        sealed trait Result extends CborSerializable
        object Result {
          case object Scheduled extends Result
          case object AlreadyScheduled extends Result
          case class Failure(ex: Throwable) extends Result
        }
      }
      case class TriggerPingJob(replyTo: ActorRef[TriggerPingJob.Result], pingJobId: PingJob.Id) extends Command
      object TriggerPingJob {
        sealed trait Result extends CborSerializable
        object Result {
          case object Triggered extends Result
          case object NotFound extends Result
          case class Failure(ex: Throwable) extends Result
        }
      }
      case class DeletePingJob(replyTo: ActorRef[DeletePingJob.Result], pingJobId: PingJob.Id) extends Command
      object DeletePingJob {
        sealed trait Result extends CborSerializable
        object Result {
          case object Deleted extends Result
          case object NotFound extends Result
          case class Failure(ex: Throwable) extends Result
        }
      }
    }

    def apply(quartzJobFactory: QuartzJobFactory, quartzProps: Properties): Behavior[Message] = Behaviors.setup { implicit context =>
      val nativeScheduler: NativeScheduler =  new StdSchedulerFactory(quartzProps).getScheduler
      val schedulerId = nativeScheduler.getSchedulerInstanceId
      context.log.info(s"Starting QuartzScheduler[schedulerId={}] actor", schedulerId)
      nativeScheduler.setJobFactory(quartzJobFactory)
      nativeScheduler.start()

      new SchedulerActor(context, nativeScheduler).idle()
        .receiveSignal {
          case (_, PostStop) =>
            context.log.info(s"QuartzScheduler[schedulerId={}] receives PostStop signal", schedulerId)
            nativeScheduler.shutdown(true)
            Behaviors.same
          case (_, PreRestart) =>
            context.log.info(s"QuartzScheduler[schedulerId={}] receives PreRestart signal", schedulerId)
            nativeScheduler.shutdown(true)
            Behaviors.same
        }
    }
  }

  class QuartzJobFactory(private val injector: Injector, private val schedulerId: String) extends JobFactory {
    override def newJob(bundle: TriggerFiredBundle, scheduler: quartz.Scheduler): Job = {
      Try {
        injector.instanceOf(bundle.getJobDetail.getJobClass)
      } match {
        case Success(job) =>
          job
        case Failure(ex)  =>
          throw new RuntimeException(s"QuartzJobFactory[$schedulerId]#newJob fails", ex)
      }
    }
  }
}