package io.argos.agent.sentinels.generic

import akka.actor.ActorRef
import com.typesafe.config.Config
import io.argos.agent.Constants
import io.argos.agent.Constants._
import io.argos.agent.bean._
import io.argos.agent.sentinels.Sentinel
import io.argos.agent.util.HostnameProvider

import scala.collection.mutable
import scala.util.Try

/**
  * Created by eric on 16/11/16.
  */
class JmxAttrSentinel(val metricsProvider: ActorRef, override val conf: Config) extends Sentinel {
  val jmxName = conf.getString(Constants.CONF_JMX_NAME)
  val jmxAttr = conf.getString(Constants.CONF_JMX_ATTR)

  private val threshold = if (conf.hasPath(CONF_THRESHOLD)) conf.getDouble(CONF_THRESHOLD) else 0.0
  private val epsilon = if (conf.hasPath(CONF_EPSILON)) conf.getLong(CONF_EPSILON) else 0.01

  val BSIZE = Try(conf.getInt(CONF_WINDOW_SIZE)).getOrElse(1)
  val wBuffer = new WindowBuffer(BSIZE, epsilon)
  val checkMean = Try(conf.getBoolean(CONF_WINDOW_MEAN)).getOrElse(false)

  // TODO create a Config Object

  override def processProtocolElement: Receive = {
    case CheckMetrics() => if (System.currentTimeMillis >= nextReact) {
      metricsProvider ! MetricsAttributeRequest(ActorProtocol.ACTION_CHECK_JMX_ATTR, jmxName, jmxAttr)
    }
    case metrics : MetricsResponse[JmxAttrValue] if (metrics.value.isDefined) => {
      val container = metrics.value.get

      wBuffer.push(container)

      if (log.isDebugEnabled) {
        log.debug("JmxAttrSentinel : Object=<{}>, Attribute=<{}> ==> <{}>", jmxName, jmxAttr, container.value)
      }
      if (checkMean && !wBuffer.meanUnderThreshold(threshold)) {
        react(container)
      } else if (!wBuffer.underThreshold(threshold)) {
        react(container)
      }
    }
  }

  def react(container: JmxAttrValue): Unit = {

    val message =
      s"""Cassandra Node ${HostnameProvider.hostname} rises an alert about attribute '${jmxAttr}' on '${jmxName}'.
         |
         |Last value : '${container.value}'
         |
         |Window Size : '${BSIZE}'
         |Configured Threshold : '${threshold}'
         |Threshold On Mean : '${checkMean}'
         |
         |""".stripMargin

    context.system.eventStream.publish(buildNotification(message))
    nextReact = System.currentTimeMillis + FREQUENCY
    wBuffer.clear()
  }



}

// --------- Window buffer utility class
// TODO merge with the one in PendingSentinel

class WindowBuffer(limit : Int, epsilon: Double) {
  val buffer = mutable.Queue[JmxAttrValue]()

  def push(elt: JmxAttrValue): Unit = {
    if (buffer.size == limit) buffer.dequeue()
    buffer.enqueue(elt)
  }

  /**
    * @param threshold
    * @return true if the mean pending tasks of the buffer are under the threshold
    */
  def meanUnderThreshold(threshold: Double) : Boolean = {
    if (buffer.size == limit) {
      Math.abs((buffer.foldLeft(0.0)((cumul, poolStats) => cumul + poolStats.value)/limit) - threshold) < epsilon
    }
    else true
  }

  /**
    * @param threshold
    * @return true if all pending tasks of the buffer are under the threshold
    */
  def underThreshold(threshold: Double) : Boolean = {
    if (buffer.size == limit) buffer.exists( entry => Math.abs(entry.value - threshold) < epsilon)
    else true
  }

  def clear() = buffer.clear()
}