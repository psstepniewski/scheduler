package scheduler.pingJob.quartz

import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.{Scheduler => AkkaScheduler}
import akka.util.Timeout
import org.quartz.SimpleScheduleBuilder.simpleSchedule
import org.quartz._
import play.api.Logging
import scheduler.pingJob.quartz.QuartzPingJob.{RETRIES_AFTER_MINUTES, jobKey}
import scheduler.pingJob.{PingJob, PingJobApi, PingJobSelector}

import java.time.{Instant, ZoneId, ZonedDateTime}
import java.util.Date
import javax.inject.Inject
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext}
import scala.util.{Failure, Success, Try}
/*
  Following `ExecutionContext` is used only for `ActorRef#ask` call. Job is executed in dedicated quartz thread pool.
  Note that because of `Await.result` quartz thread-pool is blocking. `Await.result` ensures that Job won't done
  before Future ends.

  If you reschedule Job in `Future` callbacks instead of `Await.result` call, you will get not-blocking logic. But there
  still will be small probability that application terminates after Job is finished and before new Job is scheduled.

  Default misfire policy runs this Job as soon as possible if misfire occurs.
*/
class QuartzPingJob @Inject()(pingJobSelector: PingJobSelector)(implicit ec: ExecutionContext, akkaScheduler: AkkaScheduler) extends org.quartz.Job with Logging {
  override def execute(context: JobExecutionContext): Unit = {
    val pingJobId = PingJob.Id(context.getJobDetail.getJobDataMap.getString(QuartzPingJob.PING_JOB_ID))
    val retryNo = context.getTrigger.getJobDataMap.getInt(QuartzPingJob.RETRY_NO)
    Try {
      logger.debug(s"QuartzPingJob[$pingJobId, retryNo=$retryNo] starts to execute")
      val r = pingJobSelector
        .actorRef(pingJobId)
        .ask(replyTo => PingJobApi.Command.Execute(replyTo))(QuartzPingJob.timeout, akkaScheduler)
        .map{
          case PingJobApi.Command.Execute.Result.Executed =>
            pingJobId
          case PingJobApi.Command.Execute.Result.AlreadyExecuted =>
            pingJobId
          case PingJobApi.Command.Execute.Result.EmptyState =>
            throw new RuntimeException(s"PingJob[$pingJobId] not found")
          case PingJobApi.Command.Execute.Result.Failure(ex) =>
            throw ex
        }
      Await.result(r, QuartzPingJob.timeout.duration)
    } match {
      case Success(_) =>
        context.getScheduler.deleteJob(jobKey(pingJobId))
      case Failure(e) =>
        logger.error(s"QuartzPingJob[$pingJobId, retryNo=$retryNo] fails, job will be rescheduled to the future", e)

        if(retryNo == -1) {
          logger.debug(s"QuartzPingJob[$pingJobId, retryNo=$retryNo] was triggered by endpoint. Job won't be scheduled.")
        }
        else if(retryNo > RETRIES_AFTER_MINUTES.length - 1) {
          logger.warn(s"QuartzPingJob[$pingJobId, retryNo=$retryNo] tried too many times. Give up, job won't be scheduled.")
        }
        else {
          val nextRetryNo = retryNo + 1
          QuartzPingJob.rescheduleJob(pingJobId, nextRetryNo)(context.getScheduler)
          logger.debug(s"QuartzPingJob[$pingJobId] rescheduled to next Retry[no=$nextRetryNo] which is ${RETRIES_AFTER_MINUTES(retryNo)} minutes after currentTimestamp")
        }
    }
  }
}

object QuartzPingJob {
  private[quartz] val PING_JOB_ID = "pingJobId"
  private[quartz] val RETRY_NO = "retryNo"
  private[quartz] val timeout: Timeout = 30.seconds

  private val RETRIES_AFTER_MINUTES = Array(1, 1, 1, 1, 1, 5, 5, 5, 5, 5, 15, 15, 15, 30, 30, 30, 60, 60, 60, 2*60, 2*60, 2*60, 4*60, 4*60, 4*60, 8*60, 8*60, 8*60, 24*60, 24*60, 24*60)

  def jobKey(pingJobId: PingJob.Id): JobKey = new JobKey(s"${pingJobId.value}", "PingJob")
  def triggerKey(pingJobId: PingJob.Id, retryNo: Int) = new TriggerKey(s"${pingJobId.value}_$retryNo",  "PingJob")

  def schedule(pingJobId: PingJob.Id, fireTime: Instant, retryNo: Int = 0)(nativeScheduler: Scheduler): Unit = {
    val quartzJobDetail = JobBuilder.newJob(classOf[QuartzPingJob])
      .withIdentity(jobKey(pingJobId))
      .withDescription(s"PingJob[id=$pingJobId]")
      .usingJobData(PING_JOB_ID, pingJobId.value)
      .build
    val quartzTrigger = TriggerBuilder.newTrigger
      .withIdentity(triggerKey(pingJobId, retryNo))
      .startAt(Date.from(fireTime))
      .withDescription(s"PingJob[id=$pingJobId, retryNo=$retryNo]")
      .withSchedule(simpleSchedule)
      .forJob(quartzJobDetail)
      .usingJobData(RETRY_NO, retryNo.toString)
      .build
    nativeScheduler.scheduleJob(quartzJobDetail, quartzTrigger)
  }

  def rescheduleJob(pingJobId: PingJob.Id, nextRetryNo: Int)(nativeScheduler: Scheduler): Unit = {
    val fireTime = ZonedDateTime.now(ZoneId.of("Europe/Warsaw"))
      .plusMinutes(RETRIES_AFTER_MINUTES(nextRetryNo-1))
    val quartzTrigger = TriggerBuilder.newTrigger
      .withIdentity(triggerKey(pingJobId, nextRetryNo))
      .startAt(Date.from(fireTime.toInstant))
      .withDescription(s"PingJob[id=$pingJobId, retryNo=$nextRetryNo] scheduled for $fireTime")
      .withSchedule(simpleSchedule)
      .usingJobData(RETRY_NO, nextRetryNo.toString)
      .build
    nativeScheduler.rescheduleJob(triggerKey(pingJobId, nextRetryNo-1), quartzTrigger)
  }
}
