/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kafka.server

import java.util.Properties

import junit.framework.Assert._
import org.easymock.{Capture, EasyMock}
import org.junit.Test
import kafka.integration.KafkaServerTestHarness
import kafka.utils._
import kafka.common._
import kafka.log.LogConfig
import kafka.admin.{AdminOperationException, AdminUtils}

class DynamicConfigChangeTest extends KafkaServerTestHarness {
  def generateConfigs() = List(KafkaConfig.fromProps(TestUtils.createBrokerConfig(0, zkConnect)))

  @Test
  def testConfigChange() {
    assertTrue("Should contain a ConfigHandler for topics",
               this.servers(0).dynamicConfigHandlers.contains(ConfigType.Topic))
    val oldVal: java.lang.Long = 100000L
    val newVal: java.lang.Long = 200000L
    val tp = TopicAndPartition("test", 0)
    val logProps = new Properties()
    logProps.put(LogConfig.FlushMessagesProp, oldVal.toString)
    AdminUtils.createTopic(zkClient, tp.topic, 1, 1, logProps)
    TestUtils.retry(10000) {
      val logOpt = this.servers(0).logManager.getLog(tp)
      assertTrue(logOpt.isDefined)
      assertEquals(oldVal, logOpt.get.config.flushInterval)
    }
    logProps.put(LogConfig.FlushMessagesProp, newVal.toString)
    AdminUtils.changeTopicConfig(zkClient, tp.topic, logProps)
    TestUtils.retry(10000) {
      assertEquals(newVal, this.servers(0).logManager.getLog(tp).get.config.flushInterval)
    }
  }

  // For now client config changes do not do anything. Simply verify that the call was made
  @Test
  def testClientConfigChange() {
    assertTrue("Should contain a ConfigHandler for topics",
               this.servers(0).dynamicConfigHandlers.contains(ConfigType.Client))
    val clientId = "testClient"
    val props = new Properties()
    props.put("a.b", "c")
    props.put("x.y", "z")
    AdminUtils.changeClientIdConfig(zkClient, clientId, props)
    TestUtils.retry(10000) {
      val configHandler = this.servers(0).dynamicConfigHandlers(ConfigType.Client).asInstanceOf[ClientIdConfigHandler]
      assertTrue("ClientId testClient must exist", configHandler.configPool.contains(clientId))
      assertEquals("ClientId testClient must be the only override", 1, configHandler.configPool.size)
      assertEquals("c", configHandler.configPool.get(clientId).getProperty("a.b"))
      assertEquals("z", configHandler.configPool.get(clientId).getProperty("x.y"))
    }
  }

  @Test
  def testConfigChangeOnNonExistingTopic() {
    val topic = TestUtils.tempTopic
    try {
      val logProps = new Properties()
      logProps.put(LogConfig.FlushMessagesProp, 10000: java.lang.Integer)
      AdminUtils.changeTopicConfig(zkClient, topic, logProps)
      fail("Should fail with AdminOperationException for topic doesn't exist")
    } catch {
      case e: AdminOperationException => // expected
    }
  }

  @Test
  def testProcessNotification {
    val props = new Properties()
    props.put("a.b", "10")

    // Create a mock ConfigHandler to record config changes it is asked to process
    val entityArgument = new Capture[String]()
    val propertiesArgument = new Capture[Properties]()
    val handler = EasyMock.createNiceMock(classOf[ConfigHandler])
    handler.processConfigChanges(
      EasyMock.and(EasyMock.capture(entityArgument), EasyMock.isA(classOf[String])),
      EasyMock.and(EasyMock.capture(propertiesArgument), EasyMock.isA(classOf[Properties])))
    EasyMock.expectLastCall().once()
    EasyMock.replay(handler)

    val configManager = new DynamicConfigManager(zkClient, Map(ConfigType.Topic -> handler))
    // Notifications created using the old TopicConfigManager are ignored.
    configManager.processNotification(Some("not json"))

    // Incorrect Map. No version
    try {
      val jsonMap = Map("v" -> 1, "x" -> 2)
      configManager.processNotification(Some(Json.encode(jsonMap)))
      fail("Should have thrown an Exception while parsing incorrect notification " + jsonMap)
    }
    catch {
      case t: Throwable =>
    }
    // Version is provided. EntityType is incorrect
    try {
      val jsonMap = Map("version" -> 1, "entity_type" -> "garbage", "entity_name" -> "x")
      configManager.processNotification(Some(Json.encode(jsonMap)))
      fail("Should have thrown an Exception while parsing incorrect notification " + jsonMap)
    }
    catch {
      case t: Throwable =>
    }

    // EntityName isn't provided
    try {
      val jsonMap = Map("version" -> 1, "entity_type" -> ConfigType.Topic)
      configManager.processNotification(Some(Json.encode(jsonMap)))
      fail("Should have thrown an Exception while parsing incorrect notification " + jsonMap)
    }
    catch {
      case t: Throwable =>
    }

    // Everything is provided
    val jsonMap = Map("version" -> 1, "entity_type" -> ConfigType.Topic, "entity_name" -> "x")
    configManager.processNotification(Some(Json.encode(jsonMap)))

    // Verify that processConfigChanges was only called once
    EasyMock.verify(handler)
  }
}
