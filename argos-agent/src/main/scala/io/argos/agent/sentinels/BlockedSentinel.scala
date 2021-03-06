package io.argos.agent.sentinels

import akka.actor.ActorRef
import io.argos.agent.{Messages, SentinelConfiguration}
import io.argos.agent.bean.{MetricsRequest, MetricsResponse, ThreadPoolStats}
import io.argos.agent.util.HostnameProvider
import io.argos.agent.bean._


abstract class BlockedSentinel(val metricsProvider: ActorRef, val conf: SentinelConfiguration) extends Sentinel {

  def getThreadPoolStats : MetricsRequest

  override def processProtocolElement: Receive = {

    case CheckMetrics() => if (System.currentTimeMillis >= nextReact) metricsProvider ! getThreadPoolStats
    case metrics: MetricsResponse[ThreadPoolStats] if metrics.value.isDefined => {

      val treadPool = metrics.value.get

      if (log.isDebugEnabled) {
        log.debug("BlockedSentinel : ThreadPool=<{}>, currentlyBlockedTasks=<{}>", treadPool.`type`, treadPool.currentBlockedTasks.toString)
      }

      if (treadPool.currentBlockedTasks > 0 && System.currentTimeMillis >= nextReact) {
        react(treadPool)
      }
    }
  }

  def react(info:  ThreadPoolStats): Unit = {

    val message =
      s"""Cassandra Node ${HostnameProvider.hostname} may be overloaded.
        |
        |Some actions are blocked for the Type '${info.`type`}'
        |
        |Currently blocked tasks : ${info.currentBlockedTasks}
        |Pending tasks           : ${info.pendingTasks}
        |Active Tasks            : ${info.activeTasks}
        |Available executors     : ${info.maxPoolSize}
        |
        |Total blocked tasks since node startup : ${info.totalBlockedTasks}
        |
        |Something wrong may append on this node...
      """.stripMargin

    context.system.eventStream.publish(buildNotification(conf.messageHeader.map(h => h + " \n\n--####--\n\n" + message).getOrElse(message)))

    updateNextReact()

    { }
  }
}

// --------- BlockedSentinel implementations

class CompactionExecBlockedSentinel(override val metricsProvider : ActorRef, override val conf: SentinelConfiguration) extends BlockedSentinel(metricsProvider, conf) {
  override def getThreadPoolStats: MetricsRequest = MetricsRequest(ActorProtocol.ACTION_CHECK_INTERNAL_STAGE, Messages.INTERNAL_STAGE_COMPACTION_EXEC)
}

class CounterMutationBlockedSentinel(override val metricsProvider : ActorRef, override val conf: SentinelConfiguration) extends BlockedSentinel(metricsProvider, conf) {
  override def getThreadPoolStats: MetricsRequest = MetricsRequest(ActorProtocol.ACTION_CHECK_STAGE, Messages.STAGE_COUNTER_MUTATION)
}

class GossipBlockedSentinel(override val metricsProvider : ActorRef, override val conf: SentinelConfiguration) extends BlockedSentinel(metricsProvider, conf) {
  override def getThreadPoolStats: MetricsRequest = MetricsRequest(ActorProtocol.ACTION_CHECK_INTERNAL_STAGE, Messages.INTERNAL_STAGE_GOSSIP)
}

class InternalResponseBlockedSentinel(override val metricsProvider : ActorRef, override val conf: SentinelConfiguration) extends BlockedSentinel(metricsProvider, conf) {
  override def getThreadPoolStats: MetricsRequest = MetricsRequest(ActorProtocol.ACTION_CHECK_INTERNAL_STAGE, Messages.INTERNAL_STAGE_INTERNAL_RESPONSE)
}

class MemtableFlusherBlockedSentinel(override val metricsProvider : ActorRef, override val conf: SentinelConfiguration) extends BlockedSentinel(metricsProvider, conf) {
  override def getThreadPoolStats: MetricsRequest = MetricsRequest(ActorProtocol.ACTION_CHECK_INTERNAL_STAGE, Messages.INTERNAL_STAGE_MEMTABLE_FLUSHER)
}

class MutationBlockedSentinel( override val metricsProvider : ActorRef, override val conf: SentinelConfiguration) extends BlockedSentinel(metricsProvider, conf) {
  override def getThreadPoolStats: MetricsRequest = MetricsRequest(ActorProtocol.ACTION_CHECK_STAGE, Messages.STAGE_MUTATION)
}

class ReadBlockedSentinel(override val metricsProvider : ActorRef, override val conf: SentinelConfiguration) extends BlockedSentinel(metricsProvider, conf) {
  override def getThreadPoolStats: MetricsRequest = MetricsRequest(ActorProtocol.ACTION_CHECK_STAGE, Messages.STAGE_READ)
}

class ReadRepairBlockedSentinel(override val metricsProvider : ActorRef, override val conf: SentinelConfiguration) extends BlockedSentinel(metricsProvider, conf) {
  override def getThreadPoolStats: MetricsRequest = MetricsRequest(ActorProtocol.ACTION_CHECK_STAGE, Messages.STAGE_READ_REPAIR)
}

class RequestResponseBlockedSentinel(override val metricsProvider : ActorRef, override val conf: SentinelConfiguration) extends BlockedSentinel(metricsProvider, conf) {
  override def getThreadPoolStats: MetricsRequest = MetricsRequest(ActorProtocol.ACTION_CHECK_STAGE, Messages.STAGE_REQUEST_RESPONSE)
}
