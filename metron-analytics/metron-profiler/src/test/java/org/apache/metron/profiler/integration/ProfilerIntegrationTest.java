/*
 *
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package org.apache.metron.profiler.integration;

import org.adrianwalker.multilinestring.Multiline;
import org.apache.commons.math.util.MathUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.client.HTableInterface;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.metron.common.Constants;
import org.apache.metron.common.spout.kafka.SpoutConfig;
import org.apache.metron.common.utils.SerDeUtils;
import org.apache.metron.hbase.TableProvider;
import org.apache.metron.integration.BaseIntegrationTest;
import org.apache.metron.integration.ComponentRunner;
import org.apache.metron.integration.components.FluxTopologyComponent;
import org.apache.metron.integration.components.KafkaComponent;
import org.apache.metron.integration.components.ZKServerComponent;
import org.apache.metron.profiler.hbase.ColumnBuilder;
import org.apache.metron.profiler.hbase.ValueOnlyColumnBuilder;
import org.apache.metron.test.mock.MockHTable;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.google.code.tempusfugit.temporal.Duration.seconds;
import static com.google.code.tempusfugit.temporal.Timeout.timeout;
import static com.google.code.tempusfugit.temporal.WaitFor.waitOrTimeout;

/**
 * An integration test of the Profiler topology.
 */
public class ProfilerIntegrationTest extends BaseIntegrationTest {

  private static final String TEST_RESOURCES = "../../metron-analytics/metron-profiler/src/test";
  private static final String FLUX_PATH = "../metron-profiler/src/main/flux/profiler/remote.yaml";

  /**
   * {
   * "ip_src_addr": "10.0.0.1",
   * "protocol": "HTTPS",
   * "length": 10,
   * "bytes_in": 234
   * }
   */
  @Multiline
  private String message1;

  /**
   * {
   * "ip_src_addr": "10.0.0.2",
   * "protocol": "HTTP",
   * "length": 20,
   * "bytes_in": 390
   * }
   */
  @Multiline
  private String message2;

  /**
   * {
   * "ip_src_addr": "10.0.0.3",
   * "protocol": "DNS",
   * "length": 30,
   * "bytes_in": 560
   * }
   */
  @Multiline
  private String message3;

  private ColumnBuilder columnBuilder;
  private ZKServerComponent zkComponent;
  private FluxTopologyComponent fluxComponent;
  private KafkaComponent kafkaComponent;
  private List<byte[]> input;
  private ComponentRunner runner;
  private MockHTable profilerTable;

  private static final String tableName = "profiler";
  private static final String columnFamily = "P";

  /**
   * A TableProvider that allows us to mock HBase.
   */
  public static class MockTableProvider implements TableProvider, Serializable {

    MockHTable.Provider provider = new MockHTable.Provider();

    @Override
    public HTableInterface getTable(Configuration config, String tableName) throws IOException {
      return provider.getTable(config, tableName);
    }
  }

  /**
   * Tests the first example contained within the README.
   */
  @Test
  public void testExample1() throws Exception {

    setup(TEST_RESOURCES + "/config/zookeeper/readme-example-1");

    // start the topology and write test messages to kafka
    fluxComponent.submitTopology();
    kafkaComponent.writeMessages(Constants.INDEXING_TOPIC, input);

    // verify - ensure the profile is being persisted
    waitOrTimeout(() -> profilerTable.getPutLog().size() > 0,
            timeout(seconds(90)));

    // verify - only 10.0.0.2 sends 'HTTP', thus there should be only 1 value
    List<Double> actuals = read(columnBuilder.getColumnQualifier("value"), Double.class);
    Assert.assertEquals(1, actuals.size());

    // verify - there are 5 'HTTP' each with 390 bytes
    double actual = actuals.get(0);
    Assert.assertEquals(390.0 * 5, actual, 0.01);
  }

  /**
   * Tests the second example contained within the README.
   */
  @Test
  public void testExample2() throws Exception {

    setup(TEST_RESOURCES + "/config/zookeeper/readme-example-2");

    // start the topology and write test messages to kafka
    fluxComponent.submitTopology();
    kafkaComponent.writeMessages(Constants.INDEXING_TOPIC, input);

    // expect 2 values written by the profile; one for 10.0.0.2 and another for 10.0.0.3
    final int expected = 2;

    // verify - ensure the profile is being persisted
    waitOrTimeout(() -> profilerTable.getPutLog().size() >= expected,
            timeout(seconds(90)));

    // verify - expect 2 results as 2 hosts involved; 10.0.0.2 sends 'HTTP' and 10.0.0.3 send 'DNS'
    List<Double> actuals = read(columnBuilder.getColumnQualifier("value"), Double.class);
    Assert.assertEquals(expected, actuals.size());

    // verify - 10.0.0.3 -> 1/6
    Assert.assertTrue(actuals.stream().anyMatch(val -> MathUtils.equals(val, 1.0/6.0, 0.1)));

    // verify - 10.0.0.2 -> 6/1
    Assert.assertTrue(actuals.stream().anyMatch(val -> MathUtils.equals(val, 6.0/1.0, 0.1)));
  }

  /**
   * Tests the third example contained within the README.
   */
  @Test
  public void testExample3() throws Exception {

    setup(TEST_RESOURCES + "/config/zookeeper/readme-example-3");

    // start the topology and write test messages to kafka
    fluxComponent.submitTopology();
    kafkaComponent.writeMessages(Constants.INDEXING_TOPIC, input);

    // verify - ensure the profile is being persisted
    waitOrTimeout(() -> profilerTable.getPutLog().size() > 0,
            timeout(seconds(90)));

    // verify - only 10.0.0.2 sends 'HTTP', thus there should be only 1 value
    List<Double> actuals = read(columnBuilder.getColumnQualifier("value"), Double.class);
    Assert.assertEquals(1, actuals.size());

    // verify - there are 5 'HTTP' messages each with a length of 20, thus the average should be 20
    double actual = actuals.get(0);
    Assert.assertEquals(20.0, actual, 0.01);
  }

  @Test
  public void testWriteInteger() throws Exception {

    setup(TEST_RESOURCES + "/config/zookeeper/write-integer");

    // start the topology and write test messages to kafka
    fluxComponent.submitTopology();
    kafkaComponent.writeMessages(Constants.INDEXING_TOPIC, input);

    // expect 3 values written by the profile; one for each host
    final int expected = 3;

    // verify - ensure the profile is being persisted
    waitOrTimeout(() -> profilerTable.getPutLog().size() >= expected,
            timeout(seconds(90)));

    // verify - the profile sees messages from 3 hosts; 10.0.0.[1-3]
    List<Integer> actuals = read(columnBuilder.getColumnQualifier("value"), Integer.class);
    Assert.assertEquals(3, actuals.size());

    // verify - the profile writes 10 as an integer
    actuals.forEach(actual -> Assert.assertEquals(10.0, actual, 0.01));
  }

  @Test
  public void testPercentiles() throws Exception {

    setup(TEST_RESOURCES + "/config/zookeeper/percentiles");

    // start the topology and write test messages to kafka
    fluxComponent.submitTopology();
    kafkaComponent.writeMessages(Constants.INDEXING_TOPIC, input);

    // verify - ensure the profile is being persisted
    waitOrTimeout(() -> profilerTable.getPutLog().size() > 0,
            timeout(seconds(90)));

    List<Double> actuals = read(columnBuilder.getColumnQualifier("value"), Double.class);

    // verify - the profile only cares about HTTP and only 1 host sends HTTP
    Assert.assertEquals(1, actuals.size());

    // verify - the 70th percentile of 5 x 20s = 20.0
    actuals.forEach(actual -> Assert.assertEquals(20.0, actual, 0.01));
  }

  /**
   * Reads a value written by the Profiler.
   *
   * @param column The column qualifier.
   * @param clazz The expected type of the result.
   * @param <T> The expected type of the result.
   * @return The value contained within the column.
   */
  private <T> List<T> read(byte[] column, Class<T> clazz) throws IOException {
    List<T> results = new ArrayList<>();

    final byte[] cf = Bytes.toBytes(columnFamily);
    ResultScanner scanner = profilerTable.getScanner(cf, column);
    for (Result result : scanner.next(10)) {
      if (result.containsColumn(cf, column)) {
        byte[] raw = result.getValue(cf, column);
        results.add(SerDeUtils.fromBytes(raw, clazz));
      }
    }

    return results;
  }

  public void setup(String pathToConfig) throws Exception {
    columnBuilder = new ValueOnlyColumnBuilder(columnFamily);

    // create input messages for the profiler to consume
    input = Stream.of(message1, message2, message3)
            .map(Bytes::toBytes)
            .map(m -> Collections.nCopies(5, m))
            .flatMap(l -> l.stream())
            .collect(Collectors.toList());

    // storm topology properties
    final Properties topologyProperties = new Properties() {{
      setProperty("kafka.start", SpoutConfig.Offset.BEGINNING.name());
      setProperty("profiler.workers", "1");
      setProperty("profiler.executors", "0");
      setProperty("profiler.input.topic", Constants.INDEXING_TOPIC);
      setProperty("profiler.period.duration", "5");
      setProperty("profiler.period.duration.units", "SECONDS");
      setProperty("profiler.ttl", "30");
      setProperty("profiler.ttl.units", "MINUTES");
      setProperty("profiler.hbase.salt.divisor", "10");
      setProperty("profiler.hbase.table", tableName);
      setProperty("profiler.hbase.column.family", columnFamily);
      setProperty("profiler.hbase.batch", "10");
      setProperty("profiler.hbase.flush.interval.seconds", "1");
      setProperty("profiler.profile.ttl", "20");
      setProperty("hbase.provider.impl", "" + MockTableProvider.class.getName());
    }};

    // create the mock table
    profilerTable = (MockHTable) MockHTable.Provider.addToCache(tableName, columnFamily);

    zkComponent = getZKServerComponent(topologyProperties);

    // create the input topic
    kafkaComponent = getKafkaComponent(topologyProperties,
            Arrays.asList(new KafkaComponent.Topic(Constants.INDEXING_TOPIC, 1)));

    // upload profiler configuration to zookeeper
    ConfigUploadComponent configUploadComponent = new ConfigUploadComponent()
            .withTopologyProperties(topologyProperties)
            .withGlobalConfiguration(pathToConfig)
            .withProfilerConfiguration(pathToConfig);

    // load flux definition for the profiler topology
    fluxComponent = new FluxTopologyComponent.Builder()
            .withTopologyLocation(new File(FLUX_PATH))
            .withTopologyName("profiler")
            .withTopologyProperties(topologyProperties)
            .build();

    // start all components
    runner = new ComponentRunner.Builder()
            .withComponent("zk",zkComponent)
            .withComponent("kafka", kafkaComponent)
            .withComponent("config", configUploadComponent)
            .withComponent("storm", fluxComponent)
            .withMillisecondsBetweenAttempts(15000)
            .withNumRetries(10)
            .withCustomShutdownOrder(new String[] {"storm","config","kafka","zk"})
            .build();
    runner.start();
  }

  @After
  public void tearDown() throws Exception {
    MockHTable.Provider.clear();
    if (runner != null) {
      runner.stop();
    }
  }
}