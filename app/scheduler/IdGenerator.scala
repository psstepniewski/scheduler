package scheduler

import com.softwaremill.id.pretty.{PrettyIdGenerator, StringIdGenerator}
import com.typesafe.config.Config

import javax.inject.{Inject, Singleton}

@Singleton
class IdGenerator @Inject()(config: Config) {

  private val idGenerator: StringIdGenerator = PrettyIdGenerator.distributed(config.getLong("scheduler.node.workerId"), config.getLong("scheduler.node.datacenterId"))

  def nextId(): String = s"${idGenerator.nextId()}"
}
