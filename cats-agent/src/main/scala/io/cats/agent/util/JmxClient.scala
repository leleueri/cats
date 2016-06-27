package io.cats.agent.util

import java.nio.file.Paths
import javax.management._
import javax.management.remote.{JMXConnector, JMXConnectorFactory, JMXServiceURL}

import io.cats.agent.bean.{DroppedMessageStats, StorageSpaceInfo, ThreadPoolStats}
import org.apache.cassandra.service.StorageServiceMBean

import scala.collection.JavaConverters._
import scala.util.Try
import scala.collection.mutable.Map
/**
  * Eyes of the CatsAgent... :)
  * This class gets JMX information from the Cassandra node monitored by the Cats Agent.
  *
  * @param hostname
  * @param port
  * @param user
  * @param pwd
  */
class JmxClient(hostname: String, port: Int, user: Option[String] = None, pwd: Option[String] = None) {

  // TODO Heap usage & GC stats
  // TODO READ/WRITE Latency ==> see Aaron Morton video CassSubmit 2015
  // TODO READ/WRITE Throughput ==> see Aaron Morton video CassSubmit 2015

  var connector = createConnection()
  var mbeanServerCnx = createMBeanServer

  var storageServiceProxy = initStorageServiceProxy()

  val listeners : Map[ObjectName, NotificationListener] = Map()

  private def createConnection() : JMXConnector = {
    val url = new JMXServiceURL(s"service:jmx:rmi:///jndi/rmi://${hostname}:${port}/jmxrmi")
    user match {
      case Some(login) => JMXConnectorFactory.connect(url, Map(JMXConnector.CREDENTIALS -> Array(login, pwd.get)).asJava)
      case None => JMXConnectorFactory.connect(url)
    }
  }

  private def createMBeanServer() : MBeanServerConnection = {
    // TODO addition of a listener to monitor Node DOWN : connector.addlistener...
    connector.getMBeanServerConnection
  }

  private def initStorageServiceProxy() = JMX.newMBeanProxy(mbeanServerCnx, new ObjectName("org.apache.cassandra.db:type=StorageService"), classOf[StorageServiceMBean])

  def addNotificationListener(objectName: ObjectName, listener: NotificationListener) : Unit = {
    listeners += (objectName -> listener)
    mbeanServerCnx.addNotificationListener(objectName,listener, null, null)
  }

  def reconnect() = {
    Try(connector.close())
    connector = createConnection()
    mbeanServerCnx = createMBeanServer()
    storageServiceProxy = initStorageServiceProxy()
    listeners.foreach {
      case (objName, listener) =>  mbeanServerCnx.addNotificationListener (objName, listener, null, null)
     }
  }

  def getStorageSpaceInformation() : Array[StorageSpaceInfo] = {
    def analysePath(path: String, commitLog : Boolean = false) : StorageSpaceInfo = {
      val file = Paths.get(path).toFile
      val totalSpace = file.getTotalSpace(); // total disk space in bytes.
      val freeSpace = file.getFreeSpace(); //unallocated / free disk space in bytes.

      StorageSpaceInfo(path, (totalSpace - freeSpace), freeSpace, totalSpace, commitLog)
    }

    val commitLogPath = storageServiceProxy.getCommitLogLocation
    val dataDirectories = storageServiceProxy.getAllDataFileLocations
    dataDirectories.foldLeft(Array(analysePath(commitLogPath, true)))((arr, path) => arr :+ analysePath(path) )
  }

  /**
    * @return Storage load in Bytes (space used by SSTables)
    */
  def getStorageMetricLoad() = initStorageMetric("Load")
  /**
    * @return Number of storage exceptions
    */
  def getStorageMetricExceptions() =  initStorageMetric("Exceptions")
  /**
    * @return Number of Hints to replay
    */
  def getStorageMetricTotalHints() =  initStorageMetric("TotalHints")
  /**
    * @return Number of Hints that are replaying
    */
  def getStorageMetricTotalHintsInProgess() =  initStorageMetric("TotalHintsInProgress")

  def getStorageHints() : Tuple2[Long, Long] = (getStorageMetricTotalHints, getStorageMetricTotalHintsInProgess())

  private def initStorageMetric(name: String) = mbeanServerCnx.getAttribute(new ObjectName(s"org.apache.cassandra.metrics:type=Storage,name=${name}"),"Count").toString.toLong

  /**
    * @return Information about the COUNTER_MUTATION ThreadPool
    */
  def getCounterMutationStageValues() = getStageValue("CounterMutationStage")
  /**
    * @return Information about the MUTATION ThreadPool
    */
  def getMutationStageValues() = getStageValue("MutationStage")
  /**
    * @return Information about the READ_REPAIR ThreadPool
    */
  def getReadRepairStageValues() = getStageValue("ReadRepairStage")
  /**
    * @return Information about the READ ThreadPool
    */
  def getReadStageValues() = getStageValue("ReadStage")
  /**
    * @return Information about the REQUEST_RESPONSE ThreadPool
    */
  def getRequestResponseStageValues() = getStageValue("RequestResponseStage")

  def getStageValue(stage: String) = initThreadPoolStageValues(stage, "request")

  /**
    * @return Information about the FlushWriter ThreadPool
    */
  def getMemtableFlushWriterValues() = getInternalStageValue("MemtableFlushWriter")
  /**
    * @return Information about the Compaction ThreadPool
    */
  def getCompactionExecutorValues() = getInternalStageValue("CompactionExecutor")
  /**
    * @return Information about the Gossip ThreadPool
    */
  def getGossipStageValues() = getInternalStageValue("GossipStage")
  /**
    * @return Information about the InternalResponse ThreadPool
    */
  def getInternalResponseStageValues() = getInternalStageValue("InternalResponseStage")

  def getInternalStageValue(stage: String) = initThreadPoolStageValues(stage, "internal")

  private def initThreadPoolStageValues(stage: String, path: String) : ThreadPoolStats =  {
    val active = mbeanServerCnx.getAttribute(new ObjectName(s"org.apache.cassandra.metrics:type=ThreadPools,path=${path},scope=${stage},name=ActiveTasks"),"Value").toString.toInt
    val completed = mbeanServerCnx.getAttribute(new ObjectName(s"org.apache.cassandra.metrics:type=ThreadPools,path=${path},scope=${stage},name=CompletedTasks"), "Value").toString.toInt
    val currentlyBlocked = mbeanServerCnx.getAttribute(new ObjectName(s"org.apache.cassandra.metrics:type=ThreadPools,path=${path},scope=${stage},name=CurrentlyBlockedTasks"), "Count").toString.toLong
    val poolSize = mbeanServerCnx.getAttribute(new ObjectName(s"org.apache.cassandra.metrics:type=ThreadPools,path=${path},scope=${stage},name=MaxPoolSize"), "Value").toString.toInt
    val pending = mbeanServerCnx.getAttribute(new ObjectName(s"org.apache.cassandra.metrics:type=ThreadPools,path=${path},scope=${stage},name=PendingTasks"), "Value").toString.toInt
    val totalBlocked = mbeanServerCnx.getAttribute(new ObjectName(s"org.apache.cassandra.metrics:type=ThreadPools,path=${path},scope=${stage},name=TotalBlockedTasks"), "Count").toString.toLong

    ThreadPoolStats(`type` = stage,
      activeTasks = active,
      completedTasks = completed,
      currentBlockedTasks = currentlyBlocked,
      maxPoolSize = poolSize,
      pendingTasks = pending,
      totalBlockedTasks = totalBlocked)
  }

  /**
    * @return Information about the COUNTER_MUTATION dropped messages
    */
  def getCounterMutationDroppedMessage() = getDroppedMessages("COUNTER_MUTATION")
  /**
    * @return Information about the MUTATION dropped messages
    */
  def getMutationDroppedMessage() = getDroppedMessages("MUTATION")
  /**
    * @return Information about the PAGED_RANGE dropped messages
    */
  def getPagedRangeDroppedMessage() = getDroppedMessages("PAGED_RANGE")
  /**
    * @return Information about the RANGE_SLICE dropped messages
    */
  def getRangeSliceDroppedMessage() = getDroppedMessages("RANGE_SLICE")
  /**
    * @return Information about the READ_REPAIR dropped messages
    */
  def getReadRepairDroppedMessage() = getDroppedMessages("READ_REPAIR")
  /**
    * @return Information about the READ dropped messages
    */
  def getReadDroppedMessage() = getDroppedMessages("READ")
  /**
    * @return Information about the REQUEST_RESPONSE dropped messages
    */
  def getRequestResponseDroppedMessage() = getDroppedMessages("REQUEST_RESPONSE")

  def getDroppedMessages(scope: String) : DroppedMessageStats =  {
    val attrNames = Array("Count", "FifteenMinuteRate", "FiveMinuteRate", "MeanRate", "OneMinuteRate")
    val values = mbeanServerCnx.getAttributes(new ObjectName(s"org.apache.cassandra.metrics:type=DroppedMessage,scope=${scope},name=Dropped"), attrNames)

    DroppedMessageStats(scope,
      values.get(0).asInstanceOf[Attribute].getValue.toString.toLong,
      values.get(1).asInstanceOf[Attribute].getValue.toString.toDouble,
      values.get(2).asInstanceOf[Attribute].getValue.toString.toDouble,
      values.get(3).asInstanceOf[Attribute].getValue.toString.toDouble,
      values.get(4).asInstanceOf[Attribute].getValue.toString.toDouble)
  }
}

object JmxClient {

  val DEFAULT_HOSTNAME = "127.0.0.1"
  val DEFAULT_JMX_PORT = 7199

  def apply() : JmxClient = {
    new JmxClient(DEFAULT_HOSTNAME, DEFAULT_JMX_PORT)
  }

  def apply(hostname: String, port: Int) : JmxClient = new JmxClient(hostname, port)

  def apply(hostname: String, port: Int, user: Option[String], pwd: Option[String]) : JmxClient = new JmxClient(hostname, port, user, pwd)

}
