package io.cats.agent.sentinels

import java.util.concurrent.TimeUnit

import akka.actor.ActorRef
import com.typesafe.config.Config
import io.cats.agent.Constants._
import io.cats.agent.bean.Notification
import io.cats.agent.util.{JmxClient, HostnameProvider}

import scala.concurrent.duration.FiniteDuration
import scala.util.Try

class StorageHintsSentinel(jmxAccess: JmxClient, handler: ActorRef, override val conf: Config) extends Sentinel[Array[Long]] {

  private var nextReact = System.currentTimeMillis
  private var previousValue : Array[Long] = Array(0,0)
  private val FREQUENCY = Try(conf.getDuration(CONF_FREQUENCY, TimeUnit.MILLISECONDS)).getOrElse(FiniteDuration(5, TimeUnit.MINUTES).toMillis)

  override def analyze(): Option[Array[Long]] = {
    val totalHints = jmxAccess.getStorageMetricTotalHints()
    val hintsInProgress = jmxAccess.getStorageMetricTotalHintsInProgess()

    val notificationData = Array(totalHints - previousValue(0), hintsInProgress)

    if ( (notificationData(0) > 0) && (System.currentTimeMillis >= nextReact)) {
      previousValue = Array(totalHints, hintsInProgress)
      Some(notificationData)
    } else {
      None
    }
  }

  override def react(info: Array[Long]): Unit = {
    val messageBody = s"""Cassandra Node ${HostnameProvider.hostname} has some storage hints.
         |
         | At least '${info(0)}' hints since last check
         | Currently this node's replying '${info(1)}' hints.
         |
         | Some nodes may be stopped (or there are network issues).
       """.stripMargin

    handler ! Notification(title, messageBody)
    nextReact = System.currentTimeMillis + FREQUENCY
  }
}