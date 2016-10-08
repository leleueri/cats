package io.argos.agent

import java.util.concurrent.{Executors, TimeUnit}

import akka.actor.{Actor, ActorLogging, Props}
import com.typesafe.config.ConfigFactory
import io.argos.agent.bean.{CheckMetrics, CheckNodeStatus}
import io.argos.agent.workers.MetricsProvider
import Constants._
import io.argos.agent.sentinels._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import io.argos.agent.bean._
import Messages._
import org.apache.cassandra.service.GCInspector


// to convert the entrySet of globalConfig.getConfig(CONF_OBJECT_ENTRY_NOTIFIERS)
import collection.JavaConversions._

/**
 * The "SentinelOrchestrator" actor schedule the sentinels that analyze information provided by the JMX interface of Cassandra.
 */
class SentinelOrchestrator extends Actor with ActorLogging {

  implicit val evtStream = context.system.eventStream

  val globalConfig = ConfigFactory.load()

  val configJmx = globalConfig.getConfig(CONF_OBJECT_ENTRY_METRICS)

  val metricsProvider = context.actorOf(Props(classOf[MetricsProvider], configJmx), name = "MetricsProvider")

  private def startSentinel[T <: Sentinel](clazz: Class[T], confKey: String, needsMetrics: Boolean = true) : Unit = {
    if (needsMetrics) context.actorOf(Props(clazz, metricsProvider, globalConfig.getConfig(confKey)), name = confKey.split("\\.").last)
    else context.actorOf(Props(clazz, globalConfig.getConfig(confKey)), name = confKey.split("\\.").last)
  }

  startSentinel(classOf[LoadAverageSentinel], CONF_OBJECT_ENTRY_SENTINEL_LOADAVG, false)
  startSentinel(classOf[ReadRepairBackgroundSentinel], CONF_OBJECT_ENTRY_SENTINEL_CONSISTENCY_RR_BACKGROUND)
  startSentinel(classOf[ReadRepairBlockingSentinel], CONF_OBJECT_ENTRY_SENTINEL_CONSISTENCY_RR_BLOCKING)

  startSentinel(classOf[ConnectionTimeoutSentinel], CONF_OBJECT_ENTRY_SENTINEL_CONNECTION_TIMEOUTS)

  startSentinel(classOf[DroppedCounterSentinel], CONF_OBJECT_ENTRY_SENTINEL_DROPPED_COUNTER)
  startSentinel(classOf[DroppedMutationSentinel], CONF_OBJECT_ENTRY_SENTINEL_DROPPED_MUTATION)
  startSentinel(classOf[DroppedReadSentinel], CONF_OBJECT_ENTRY_SENTINEL_DROPPED_READ)
  startSentinel(classOf[DroppedReadRepairSentinel], CONF_OBJECT_ENTRY_SENTINEL_DROPPED_READ_REPAIR)
  startSentinel(classOf[DroppedPageRangeSentinel], CONF_OBJECT_ENTRY_SENTINEL_DROPPED_PAGE)
  startSentinel(classOf[DroppedRangeSliceSentinel], CONF_OBJECT_ENTRY_SENTINEL_DROPPED_RANGE)
  startSentinel(classOf[DroppedRequestResponseSentinel], CONF_OBJECT_ENTRY_SENTINEL_DROPPED_REQ_RESP)

  startSentinel(classOf[CounterMutationBlockedSentinel], CONF_OBJECT_ENTRY_SENTINEL_BLOCKED_STAGE_COUNTER_MUTATION)
  startSentinel(classOf[GossipBlockedSentinel], CONF_OBJECT_ENTRY_SENTINEL_BLOCKED_STAGE_GOSSIP)
  startSentinel(classOf[InternalResponseBlockedSentinel], CONF_OBJECT_ENTRY_SENTINEL_BLOCKED_STAGE_INTERNAL)
  startSentinel(classOf[MemtableFlusherBlockedSentinel], CONF_OBJECT_ENTRY_SENTINEL_BLOCKED_STAGE_MEMTABLE)
  startSentinel(classOf[MutationBlockedSentinel], CONF_OBJECT_ENTRY_SENTINEL_BLOCKED_STAGE_MUTATION)
  startSentinel(classOf[ReadBlockedSentinel], CONF_OBJECT_ENTRY_SENTINEL_BLOCKED_STAGE_READ)
  startSentinel(classOf[CompactionExecBlockedSentinel], CONF_OBJECT_ENTRY_SENTINEL_BLOCKED_STAGE_COMPACTION)
  startSentinel(classOf[ReadRepairBlockedSentinel], CONF_OBJECT_ENTRY_SENTINEL_BLOCKED_STAGE_READ_REPAIR)
  startSentinel(classOf[RequestResponseBlockedSentinel], CONF_OBJECT_ENTRY_SENTINEL_BLOCKED_STAGE_REQUEST_RESPONSE)

  startSentinel(classOf[CounterMutationPendingSentinel], CONF_OBJECT_ENTRY_SENTINEL_PENDING_STAGE_COUNTER_MUTATION)
  startSentinel(classOf[GossipPendingSentinel], CONF_OBJECT_ENTRY_SENTINEL_PENDING_STAGE_GOSSIP)
  startSentinel(classOf[InternalResponsePendingSentinel], CONF_OBJECT_ENTRY_SENTINEL_PENDING_STAGE_INTERNAL)
  startSentinel(classOf[MemtableFlusherPendingSentinel], CONF_OBJECT_ENTRY_SENTINEL_PENDING_STAGE_MEMTABLE)
  startSentinel(classOf[MutationPendingSentinel], CONF_OBJECT_ENTRY_SENTINEL_PENDING_STAGE_MUTATION)
  startSentinel(classOf[ReadPendingSentinel], CONF_OBJECT_ENTRY_SENTINEL_PENDING_STAGE_READ)
  startSentinel(classOf[CompactionExecPendingSentinel], CONF_OBJECT_ENTRY_SENTINEL_PENDING_STAGE_COMPACTION)
  startSentinel(classOf[ReadRepairPendingSentinel], CONF_OBJECT_ENTRY_SENTINEL_PENDING_STAGE_READ_REPAIR)
  startSentinel(classOf[RequestResponsePendingSentinel], CONF_OBJECT_ENTRY_SENTINEL_PENDING_STAGE_REQUEST_RESPONSE)

  startSentinel(classOf[StorageSpaceSentinel], CONF_OBJECT_ENTRY_SENTINEL_STORAGE_SPACE)
  startSentinel(classOf[StorageHintsSentinel], CONF_OBJECT_ENTRY_SENTINEL_STORAGE_HINTS)
  startSentinel(classOf[StorageExceptionSentinel], CONF_OBJECT_ENTRY_SENTINEL_STORAGE_EXCEPTION)

  startSentinel(classOf[GCSentinel], CONF_OBJECT_ENTRY_SENTINEL_GC )

  startSentinel(classOf[AvailabilitySentinel], CONF_OBJECT_ENTRY_SENTINEL_AVAILABLE)

  startSentinel(classOf[InternalNotificationsSentinel], CONF_OBJECT_ENTRY_SENTINEL_JMX_NOTIFICATION, false)

  // initialize the frequency of metrics control
  context.system.scheduler.schedule(1 second,
    Duration.create(globalConfig.getDuration(CONF_ORCHESTRATOR_INTERVAL).getSeconds, TimeUnit.SECONDS),
    self,
    CHECK_METRICS)(ExecutionContext.fromExecutor(Executors.newSingleThreadScheduledExecutor()))

  log.info("SentinelOrchestrator is running...")

  override def receive = {
    case CHECK_METRICS => processControls
    case NodeStatus(status) if (status == Messages.OFFLINE_NODE) => context.become(offline)
  }

  def offline : Receive = {
    case CHECK_METRICS => metricsProvider ! CheckNodeStatus
    case NodeStatus(status) if (status == Messages.ONLINE_NODE) => context.unbecome()
  }

  private def processControls: Unit = {
      log.debug("{} received", CHECK_METRICS);
      evtStream.publish(CheckMetrics())
  }
}
