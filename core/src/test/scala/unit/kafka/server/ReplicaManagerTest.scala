/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kafka.server

import java.io.File
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean

import kafka.log.LogConfig
import kafka.utils.{MockScheduler, MockTime, TestUtils, ZkUtils}
import TestUtils.createBroker
import kafka.utils.timer.MockTimer
import org.I0Itec.zkclient.ZkClient
import org.apache.kafka.common.metrics.Metrics
import org.apache.kafka.common.protocol.Errors
import org.apache.kafka.common.record._
import org.apache.kafka.common.requests.{IsolationLevel, LeaderAndIsrRequest, PartitionState}
import org.apache.kafka.common.requests.ProduceResponse.PartitionResponse
import org.apache.kafka.common.requests.FetchRequest.PartitionData
import org.apache.kafka.common.{Node, TopicPartition}
import org.easymock.EasyMock
import org.junit.Assert.{assertEquals, assertTrue}
import org.junit.{After, Before, Test}

import scala.collection.JavaConverters._
import scala.collection.Map

class ReplicaManagerTest {

  val topic = "test-topic"
  val time = new MockTime
  val metrics = new Metrics
  var zkClient : ZkClient = _
  var zkUtils : ZkUtils = _
    
  @Before
  def setUp() {
    zkClient = EasyMock.createMock(classOf[ZkClient])
    zkUtils = ZkUtils(zkClient, isZkSecurityEnabled = false)
  }
  
  @After
  def tearDown() {
    metrics.close()
  }

  @Test
  def testHighWaterMarkDirectoryMapping() {
    val props = TestUtils.createBrokerConfig(1, TestUtils.MockZkConnect)
    val config = KafkaConfig.fromProps(props)
    val mockLogMgr = TestUtils.createLogManager(config.logDirs.map(new File(_)).toArray)
    val rm = new ReplicaManager(config, metrics, time, zkUtils, new MockScheduler(time), mockLogMgr,
      new AtomicBoolean(false), QuotaFactory.instantiate(config, metrics, time).follower, new BrokerTopicStats,
      new MetadataCache(config.brokerId))
    try {
      val partition = rm.getOrCreatePartition(new TopicPartition(topic, 1))
      partition.getOrCreateReplica(1)
      rm.checkpointHighWatermarks()
    } finally {
      // shutdown the replica manager upon test completion
      rm.shutdown(false)
    }
  }

  @Test
  def testHighwaterMarkRelativeDirectoryMapping() {
    val props = TestUtils.createBrokerConfig(1, TestUtils.MockZkConnect)
    props.put("log.dir", TestUtils.tempRelativeDir("data").getAbsolutePath)
    val config = KafkaConfig.fromProps(props)
    val mockLogMgr = TestUtils.createLogManager(config.logDirs.map(new File(_)).toArray)
    val rm = new ReplicaManager(config, metrics, time, zkUtils, new MockScheduler(time), mockLogMgr,
      new AtomicBoolean(false), QuotaFactory.instantiate(config, metrics, time).follower, new BrokerTopicStats,
      new MetadataCache(config.brokerId))
    try {
      val partition = rm.getOrCreatePartition(new TopicPartition(topic, 1))
      partition.getOrCreateReplica(1)
      rm.checkpointHighWatermarks()
    } finally {
      // shutdown the replica manager upon test completion
      rm.shutdown(checkpointHW = false)
    }
  }

  @Test
  def testIllegalRequiredAcks() {
    val props = TestUtils.createBrokerConfig(1, TestUtils.MockZkConnect)
    val config = KafkaConfig.fromProps(props)
    val mockLogMgr = TestUtils.createLogManager(config.logDirs.map(new File(_)).toArray)
    val rm = new ReplicaManager(config, metrics, time, zkUtils, new MockScheduler(time), mockLogMgr,
      new AtomicBoolean(false), QuotaFactory.instantiate(config, metrics, time).follower, new BrokerTopicStats,
      new MetadataCache(config.brokerId), Option(this.getClass.getName))
    try {
      def callback(responseStatus: Map[TopicPartition, PartitionResponse]) = {
        assert(responseStatus.values.head.error == Errors.INVALID_REQUIRED_ACKS)
      }
      rm.appendRecords(
        timeout = 0,
        requiredAcks = 3,
        internalTopicsAllowed = false,
        isFromClient = true,
        entriesPerPartition = Map(new TopicPartition("test1", 0) -> MemoryRecords.withRecords(CompressionType.NONE,
          new SimpleRecord("first message".getBytes))),
        responseCallback = callback)
    } finally {
      rm.shutdown(checkpointHW = false)
    }

    TestUtils.verifyNonDaemonThreadsStatus(this.getClass.getName)
  }

  @Test
  def testClearPurgatoryOnBecomingFollower() {
    val props = TestUtils.createBrokerConfig(0, TestUtils.MockZkConnect)
    props.put("log.dir", TestUtils.tempRelativeDir("data").getAbsolutePath)
    val config = KafkaConfig.fromProps(props)
    val logProps = new Properties()
    val mockLogMgr = TestUtils.createLogManager(config.logDirs.map(new File(_)).toArray, LogConfig(logProps))
    val aliveBrokers = Seq(createBroker(0, "host0", 0), createBroker(1, "host1", 1))
    val metadataCache = EasyMock.createMock(classOf[MetadataCache])
    EasyMock.expect(metadataCache.getAliveBrokers).andReturn(aliveBrokers).anyTimes()
    EasyMock.replay(metadataCache)
    val rm = new ReplicaManager(config, metrics, time, zkUtils, new MockScheduler(time), mockLogMgr,
      new AtomicBoolean(false), QuotaFactory.instantiate(config, metrics, time).follower, new BrokerTopicStats,
      metadataCache)

    try {
      var produceCallbackFired = false
      def produceCallback(responseStatus: Map[TopicPartition, PartitionResponse]) = {
        assertEquals("Should give NotLeaderForPartitionException", Errors.NOT_LEADER_FOR_PARTITION,
          responseStatus.values.head.error)
        produceCallbackFired = true
      }

      var fetchCallbackFired = false
      def fetchCallback(responseStatus: Seq[(TopicPartition, FetchPartitionData)]) = {
        assertEquals("Should give NotLeaderForPartitionException", Errors.NOT_LEADER_FOR_PARTITION,
          responseStatus.map(_._2).head.error)
        fetchCallbackFired = true
      }

      val brokerList = Seq[Integer](0, 1).asJava
      val brokerSet = Set[Integer](0, 1).asJava

      val partition = rm.getOrCreatePartition(new TopicPartition(topic, 0))
      partition.getOrCreateReplica(0)
      // Make this replica the leader.
      val leaderAndIsrRequest1 = new LeaderAndIsrRequest.Builder(0, 0,
        collection.immutable.Map(new TopicPartition(topic, 0) -> new PartitionState(0, 0, 0, brokerList, 0, brokerSet)).asJava,
        Set(new Node(0, "host1", 0), new Node(1, "host2", 1)).asJava).build()
      rm.becomeLeaderOrFollower(0, leaderAndIsrRequest1, (_, _) => ())
      rm.getLeaderReplicaIfLocal(new TopicPartition(topic, 0))

      val records = MemoryRecords.withRecords(CompressionType.NONE, new SimpleRecord("first message".getBytes()))
      appendRecords(rm, Map(new TopicPartition(topic, 0) -> records), produceCallback)

      // Fetch some messages
      fetchAsConsumer(rm, Seq(new TopicPartition(topic, 0) -> new PartitionData(0, 0, 100000)), fetchCallback,
        minBytes = 100000)

      // Make this replica the follower
      val leaderAndIsrRequest2 = new LeaderAndIsrRequest.Builder(0, 0,
        collection.immutable.Map(new TopicPartition(topic, 0) -> new PartitionState(0, 1, 1, brokerList, 0, brokerSet)).asJava,
        Set(new Node(0, "host1", 0), new Node(1, "host2", 1)).asJava).build()
      rm.becomeLeaderOrFollower(1, leaderAndIsrRequest2, (_, _) => ())

      assertTrue(produceCallbackFired)
      assertTrue(fetchCallbackFired)
    } finally {
      rm.shutdown(checkpointHW = false)
    }
  }

  @Test
  def testReadCommittedFetchLimitedAtLSO(): Unit = {
    val timer = new MockTimer
    val replicaManager = setupReplicaManagerWithMockedPurgatories(timer)

    try {
      val brokerList = Seq[Integer](0, 1).asJava
      val brokerSet = Set[Integer](0, 1).asJava

      val partition = replicaManager.getOrCreatePartition(new TopicPartition(topic, 0))
      partition.getOrCreateReplica(0)

      // Make this replica the leader.
      val leaderAndIsrRequest1 = new LeaderAndIsrRequest.Builder(0, 0,
        collection.immutable.Map(new TopicPartition(topic, 0) -> new PartitionState(0, 0, 0, brokerList, 0, brokerSet)).asJava,
        Set(new Node(0, "host1", 0), new Node(1, "host2", 1)).asJava).build()
      replicaManager.becomeLeaderOrFollower(0, leaderAndIsrRequest1, (_, _) => ())
      replicaManager.getLeaderReplicaIfLocal(new TopicPartition(topic, 0))

      def produceCallback(responseStatus: Map[TopicPartition, PartitionResponse]) =
        responseStatus.values.foreach { status =>
          assertEquals(Errors.NONE, status.error)
        }

      val producerId = 234L
      val epoch = 5.toShort

      // write a few batches as part of a transaction
      val numRecords = 3
      for (sequence <- 0 until numRecords) {
        val records = MemoryRecords.withTransactionalRecords(CompressionType.NONE, producerId, epoch, sequence,
          new SimpleRecord(s"message $sequence".getBytes))
        appendRecords(replicaManager, Map(new TopicPartition(topic, 0) -> records), produceCallback)
      }

      var fetchCallbackFired = false
      var fetchError = Errors.NONE
      var fetchedRecords: Records = null
      def fetchCallback(responseStatus: Seq[(TopicPartition, FetchPartitionData)]) = {
        fetchError = responseStatus.map(_._2).head.error
        fetchedRecords = responseStatus.map(_._2).head.records
        fetchCallbackFired = true
      }

      // fetch as follower to advance the high watermark
      fetchAsFollower(replicaManager, Seq(new TopicPartition(topic, 0) -> new PartitionData(numRecords, 0, 100000)),
        fetchCallback, isolationLevel = IsolationLevel.READ_UNCOMMITTED)

      // fetch should return empty since LSO should be stuck at 0
      fetchAsConsumer(replicaManager, Seq(new TopicPartition(topic, 0) -> new PartitionData(0, 0, 100000)),
        fetchCallback, isolationLevel = IsolationLevel.READ_COMMITTED)
      assertTrue(fetchCallbackFired)
      assertEquals(Errors.NONE, fetchError)
      assertTrue(fetchedRecords.batches.asScala.isEmpty)
      fetchCallbackFired = false

      // delayed fetch should timeout and return nothing
      fetchAsConsumer(replicaManager, Seq(new TopicPartition(topic, 0) -> new PartitionData(0, 0, 100000)),
        fetchCallback, isolationLevel = IsolationLevel.READ_COMMITTED, minBytes = 1000)
      timer.advanceClock(1001)

      assertTrue(fetchCallbackFired)
      assertEquals(Errors.NONE, fetchError)
      assertTrue(fetchedRecords.batches.asScala.isEmpty)
      fetchCallbackFired = false

      // now commit the transaction
      val endTxnMarker = new EndTransactionMarker(ControlRecordType.COMMIT, 0)
      val commitRecordBatch = MemoryRecords.withEndTransactionMarker(producerId, epoch, endTxnMarker)
      appendRecords(replicaManager, Map(new TopicPartition(topic, 0) -> commitRecordBatch), produceCallback,
        isFromClient = false)

      // the LSO has advanced, but the appended commit marker has not been replicated, so
      // none of the data from the transaction should be visible yet
      fetchAsConsumer(replicaManager, Seq(new TopicPartition(topic, 0) -> new PartitionData(0, 0, 100000)),
        fetchCallback, isolationLevel = IsolationLevel.READ_COMMITTED)

      assertTrue(fetchCallbackFired)
      assertEquals(Errors.NONE, fetchError)
      assertTrue(fetchedRecords.batches.asScala.isEmpty)
      fetchCallbackFired = false

      // fetch as follower to advance the high watermark
      fetchAsFollower(replicaManager, Seq(new TopicPartition(topic, 0) -> new PartitionData(numRecords + 1, 0, 100000)),
        fetchCallback, isolationLevel = IsolationLevel.READ_UNCOMMITTED)

      // now all of the records should be fetchable
      fetchAsConsumer(replicaManager, Seq(new TopicPartition(topic, 0) -> new PartitionData(0, 0, 100000)),
        fetchCallback, isolationLevel = IsolationLevel.READ_COMMITTED)

      assertTrue(fetchCallbackFired)
      assertEquals(Errors.NONE, fetchError)
      assertEquals(numRecords + 1, fetchedRecords.batches.asScala.size)
    } finally {
      replicaManager.shutdown(checkpointHW = false)
    }
  }

  @Test
  def testDelayedFetchIncludesAbortedTransactions(): Unit = {
    val timer = new MockTimer
    val replicaManager = setupReplicaManagerWithMockedPurgatories(timer)

    try {
      val brokerList: java.util.List[Integer] = Seq[Integer](0, 1).asJava
      val brokerSet: java.util.Set[Integer] = Set[Integer](0, 1).asJava

      val partition = replicaManager.getOrCreatePartition(new TopicPartition(topic, 0))
      partition.getOrCreateReplica(0)

      // Make this replica the leader.
      val leaderAndIsrRequest1 = new LeaderAndIsrRequest.Builder(0, 0,
        collection.immutable.Map(new TopicPartition(topic, 0) -> new PartitionState(0, 0, 0, brokerList, 0, brokerSet)).asJava,
        Set(new Node(0, "host1", 0), new Node(1, "host2", 1)).asJava).build()
      replicaManager.becomeLeaderOrFollower(0, leaderAndIsrRequest1, (_, _) => ())
      replicaManager.getLeaderReplicaIfLocal(new TopicPartition(topic, 0))

      def produceCallback(responseStatus: Map[TopicPartition, PartitionResponse]) =
        responseStatus.values.foreach { status =>
          assertEquals(Errors.NONE, status.error)
        }

      val producerId = 234L
      val epoch = 5.toShort

      // write a few batches as part of a transaction
      val numRecords = 3
      for (sequence <- 0 until numRecords) {
        val records = MemoryRecords.withTransactionalRecords(CompressionType.NONE, producerId, epoch, sequence,
          new SimpleRecord(s"message $sequence".getBytes))
        appendRecords(replicaManager, Map(new TopicPartition(topic, 0) -> records), produceCallback)
      }

      // now abort the transaction
      val endTxnMarker = new EndTransactionMarker(ControlRecordType.ABORT, 0)
      val abortRecordBatch = MemoryRecords.withEndTransactionMarker(producerId, epoch, endTxnMarker)
      appendRecords(replicaManager, Map(new TopicPartition(topic, 0) -> abortRecordBatch), produceCallback,
        isFromClient = false)

      // fetch as follower to advance the high watermark
      fetchAsFollower(replicaManager, Seq(new TopicPartition(topic, 0) -> new PartitionData(numRecords + 1, 0, 100000)),
        responseState => (), isolationLevel = IsolationLevel.READ_UNCOMMITTED)

      var fetchDataOpt: Option[FetchPartitionData] = None
      def fetchCallback(responseStatus: Seq[(TopicPartition, FetchPartitionData)]) = {
        fetchDataOpt = Some(responseStatus.map(_._2).head)
      }

      // Set the minBytes in order force this request to enter purgatory. When it returns, we should still
      // see the newly aborted transaction.
      fetchAsConsumer(replicaManager, Seq(new TopicPartition(topic, 0) -> new PartitionData(0, 0, 100000)),
        fetchCallback, isolationLevel = IsolationLevel.READ_COMMITTED, minBytes = 10000)
      assertTrue(fetchDataOpt.isEmpty)

      timer.advanceClock(1001)
      assertTrue(fetchDataOpt.isDefined)

      val fetchData = fetchDataOpt.get
      assertEquals(Errors.NONE, fetchData.error)
      assertEquals(numRecords + 1, fetchData.records.records.asScala.size)
      assertTrue(fetchData.abortedTransactions.isDefined)
      assertEquals(1, fetchData.abortedTransactions.get.size)

      val abortedTransaction = fetchData.abortedTransactions.get.head
      assertEquals(0L, abortedTransaction.firstOffset)
      assertEquals(producerId, abortedTransaction.producerId)
    } finally {
      replicaManager.shutdown(checkpointHW = false)
    }
  }

  @Test
  def testFetchBeyondHighWatermarkReturnEmptyResponse() {
    val props = TestUtils.createBrokerConfig(1, TestUtils.MockZkConnect)
    props.put("log.dir", TestUtils.tempRelativeDir("data").getAbsolutePath)
    props.put("broker.id", Int.box(0))
    val config = KafkaConfig.fromProps(props)
    val logProps = new Properties()
    val mockLogMgr = TestUtils.createLogManager(config.logDirs.map(new File(_)).toArray, LogConfig(logProps))
    val aliveBrokers = Seq(createBroker(0, "host0", 0), createBroker(1, "host1", 1), createBroker(1, "host2", 2))
    val metadataCache = EasyMock.createMock(classOf[MetadataCache])
    EasyMock.expect(metadataCache.getAliveBrokers).andReturn(aliveBrokers).anyTimes()
    EasyMock.expect(metadataCache.isBrokerAlive(EasyMock.eq(0))).andReturn(true).anyTimes()
    EasyMock.expect(metadataCache.isBrokerAlive(EasyMock.eq(1))).andReturn(true).anyTimes()
    EasyMock.expect(metadataCache.isBrokerAlive(EasyMock.eq(2))).andReturn(true).anyTimes()
    EasyMock.replay(metadataCache)
    val rm = new ReplicaManager(config, metrics, time, zkUtils, new MockScheduler(time), mockLogMgr,
      new AtomicBoolean(false), QuotaFactory.instantiate(config, metrics, time).follower, new BrokerTopicStats,
      metadataCache, Option(this.getClass.getName))

    try {
      val brokerList: java.util.List[Integer] = Seq[Integer](0, 1, 2).asJava
      val brokerSet: java.util.Set[Integer] = Set[Integer](0, 1, 2).asJava

      val partition = rm.getOrCreatePartition(new TopicPartition(topic, 0))
      partition.getOrCreateReplica(0)

      // Make this replica the leader.
      val leaderAndIsrRequest1 = new LeaderAndIsrRequest.Builder(0, 0,
        collection.immutable.Map(new TopicPartition(topic, 0) -> new PartitionState(0, 0, 0, brokerList, 0, brokerSet)).asJava,
        Set(new Node(0, "host1", 0), new Node(1, "host2", 1), new Node(2, "host2", 2)).asJava).build()
      rm.becomeLeaderOrFollower(0, leaderAndIsrRequest1, (_, _) => ())
      rm.getLeaderReplicaIfLocal(new TopicPartition(topic, 0))

      def produceCallback(responseStatus: Map[TopicPartition, PartitionResponse]) = {}

      // Append a couple of messages.
      for(i <- 1 to 2) {
        val records = TestUtils.singletonRecords(s"message $i".getBytes)
        appendRecords(rm, Map(new TopicPartition(topic, 0) -> records), produceCallback)
      }

      var fetchCallbackFired = false
      var fetchError = Errors.NONE
      var fetchedRecords: Records = null
      def fetchCallback(responseStatus: Seq[(TopicPartition, FetchPartitionData)]) = {
        fetchError = responseStatus.map(_._2).head.error
        fetchedRecords = responseStatus.map(_._2).head.records
        fetchCallbackFired = true
      }

      // Fetch a message above the high watermark as a follower
      fetchAsFollower(rm, Seq(new TopicPartition(topic, 0) -> new PartitionData(1, 0, 100000)), fetchCallback)
      assertTrue(fetchCallbackFired)
      assertEquals("Should not give an exception", Errors.NONE, fetchError)
      assertTrue("Should return some data", fetchedRecords.batches.iterator.hasNext)
      fetchCallbackFired = false

      // Fetch a message above the high watermark as a consumer
      fetchAsConsumer(rm, Seq(new TopicPartition(topic, 0) -> new PartitionData(1, 0, 100000)), fetchCallback)
      assertTrue(fetchCallbackFired)
      assertEquals("Should not give an exception", Errors.NONE, fetchError)
      assertEquals("Should return empty response", MemoryRecords.EMPTY, fetchedRecords)
    } finally {
      rm.shutdown(checkpointHW = false)
    }
  }

  private def appendRecords(replicaManager: ReplicaManager,
                            entriesPerPartition: Map[TopicPartition, MemoryRecords],
                            responseCallback: Map[TopicPartition, PartitionResponse] => Unit,
                            isFromClient: Boolean = true): Unit = {
    replicaManager.appendRecords(
      timeout = 1000,
      requiredAcks = -1,
      internalTopicsAllowed = false,
      isFromClient = isFromClient,
      entriesPerPartition = entriesPerPartition,
      responseCallback = responseCallback)
  }

  private def fetchAsConsumer(replicaManager: ReplicaManager,
                              fetchInfos: Seq[(TopicPartition, PartitionData)],
                              fetchCallback: Seq[(TopicPartition, FetchPartitionData)] => Unit,
                              minBytes: Int = 0,
                              isolationLevel: IsolationLevel = IsolationLevel.READ_UNCOMMITTED): Unit = {
    fetchMessages(replicaManager, replicaId = -1, fetchInfos, fetchCallback, minBytes, isolationLevel)
  }

  private def fetchAsFollower(replicaManager: ReplicaManager,
                              fetchInfos: Seq[(TopicPartition, PartitionData)],
                              fetchCallback: Seq[(TopicPartition, FetchPartitionData)] => Unit,
                              minBytes: Int = 0,
                              isolationLevel: IsolationLevel = IsolationLevel.READ_UNCOMMITTED): Unit = {
    fetchMessages(replicaManager, replicaId = 1, fetchInfos, fetchCallback, minBytes, isolationLevel)
  }

  private def fetchMessages(replicaManager: ReplicaManager,
                            replicaId: Int,
                            fetchInfos: Seq[(TopicPartition, PartitionData)],
                            fetchCallback: Seq[(TopicPartition, FetchPartitionData)] => Unit,
                            minBytes: Int,
                            isolationLevel: IsolationLevel): Unit = {
    replicaManager.fetchMessages(
      timeout = 1000,
      replicaId = replicaId,
      fetchMinBytes = minBytes,
      fetchMaxBytes = Int.MaxValue,
      hardMaxBytesLimit = false,
      fetchInfos = fetchInfos,
      responseCallback = fetchCallback,
      isolationLevel = isolationLevel)
  }

  private def setupReplicaManagerWithMockedPurgatories(timer: MockTimer): ReplicaManager = {
    val props = TestUtils.createBrokerConfig(1, TestUtils.MockZkConnect)
    props.put("log.dir", TestUtils.tempRelativeDir("data").getAbsolutePath)
    props.put("broker.id", Int.box(0))
    val config = KafkaConfig.fromProps(props)
    val logProps = new Properties()
    val mockLogMgr = TestUtils.createLogManager(config.logDirs.map(new File(_)).toArray, LogConfig(logProps))
    val aliveBrokers = Seq(createBroker(0, "host0", 0), createBroker(1, "host1", 1))
    val metadataCache = EasyMock.createMock(classOf[MetadataCache])
    EasyMock.expect(metadataCache.getAliveBrokers).andReturn(aliveBrokers).anyTimes()
    EasyMock.expect(metadataCache.isBrokerAlive(EasyMock.eq(0))).andReturn(true).anyTimes()
    EasyMock.expect(metadataCache.isBrokerAlive(EasyMock.eq(1))).andReturn(true).anyTimes()
    EasyMock.replay(metadataCache)

    val mockProducePurgatory = new DelayedOperationPurgatory[DelayedProduce](
      purgatoryName = "Produce", timer, reaperEnabled = false)
    val mockFetchPurgatory = new DelayedOperationPurgatory[DelayedFetch](
      purgatoryName = "Fetch", timer, reaperEnabled = false)
    val mockDeleteRecordsPurgatory = new DelayedOperationPurgatory[DelayedDeleteRecords](
      purgatoryName = "DeleteRecords", timer, reaperEnabled = false)

    new ReplicaManager(config, metrics, time, zkUtils, new MockScheduler(time), mockLogMgr,
      new AtomicBoolean(false), QuotaFactory.instantiate(config, metrics, time).follower, new BrokerTopicStats,
      metadataCache, mockProducePurgatory, mockFetchPurgatory, mockDeleteRecordsPurgatory, Option(this.getClass.getName))
  }

}
