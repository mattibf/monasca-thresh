/*
 * Copyright (c) 2014 Hewlett-Packard Development Company, L.P.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package monasca.thresh.infrastructure.thresholding;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;

import com.hpcloud.mon.common.model.alarm.AlarmExpression;
import com.hpcloud.mon.common.model.alarm.AlarmSubExpression;
import com.hpcloud.mon.common.model.metric.Metric;
import com.hpcloud.mon.common.model.metric.MetricDefinition;
import monasca.thresh.domain.model.MetricDefinitionAndTenantId;
import monasca.thresh.domain.model.SubAlarm;
import monasca.thresh.domain.service.MetricDefinitionDAO;
import monasca.thresh.domain.service.SubAlarmMetricDefinition;
import com.hpcloud.streaming.storm.Streams;

import backtype.storm.Testing;
import backtype.storm.task.OutputCollector;
import backtype.storm.task.TopologyContext;
import backtype.storm.testing.MkTupleParam;
import backtype.storm.tuple.Tuple;
import backtype.storm.tuple.Values;

import org.mockito.verification.VerificationMode;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Test
public class MetricFilteringBoltTest {
  private List<SubAlarm> subAlarms;
  private List<SubAlarm> duplicateMetricSubAlarms;
  private final static String TEST_TENANT_ID = "42";
  private long metricTimestamp = System.currentTimeMillis() / 1000; // Make sure the metric
                                                                    // timestamp is always unique

  @BeforeMethod
  protected void beforeMethod() {

    final String expression =
        "avg(hpcs.compute.cpu{instance_id=123,device=42}, 1) > 5 "
            + "and max(hpcs.compute.mem{instance_id=123,device=42}) > 80 "
            + "and max(hpcs.compute.load{instance_id=123,device=42}) > 5";
    subAlarms = createSubAlarmsForAlarm("111111112222222222233333333334", expression);

    duplicateMetricSubAlarms =
        createSubAlarmsForAlarm(UUID.randomUUID().toString(),
            "max(hpcs.compute.load{instance_id=123,device=42}) > 8");
    subAlarms.addAll(duplicateMetricSubAlarms);
  }

  private List<SubAlarm> createSubAlarmsForAlarm(final String alarmId, final String expression) {
    final AlarmExpression alarmExpression = new AlarmExpression(expression);
    final List<AlarmSubExpression> subExpressions = alarmExpression.getSubExpressions();
    final List<SubAlarm> result = new ArrayList<SubAlarm>(subExpressions.size());
    for (int i = 0; i < subExpressions.size(); i++) {
      final SubAlarm subAlarm =
          new SubAlarm(UUID.randomUUID().toString(), alarmId, subExpressions.get(i));
      result.add(subAlarm);
    }
    return result;
  }

  private MockMetricFilteringBolt createBolt(
      List<SubAlarmMetricDefinition> initialMetricDefinitions, final OutputCollector collector,
      boolean willEmit) {
    final MetricDefinitionDAO dao = mock(MetricDefinitionDAO.class);
    when(dao.findForAlarms()).thenReturn(initialMetricDefinitions);
    MockMetricFilteringBolt bolt = new MockMetricFilteringBolt(dao);

    final Map<String, String> config = new HashMap<>();
    final TopologyContext context = mock(TopologyContext.class);
    bolt.prepare(config, context, collector);

    if (willEmit) {
      // Validate the prepare emits the initial Metric Definitions
      for (final SubAlarmMetricDefinition metricDefinition : initialMetricDefinitions) {
        verify(collector, times(1)).emit(
            new Values(metricDefinition.getMetricDefinitionAndTenantId(), null));
      }
    }
    return bolt;
  }

  public void testLagging() {
    final OutputCollector collector = mock(OutputCollector.class);

    final MockMetricFilteringBolt bolt =
        createBolt(new ArrayList<SubAlarmMetricDefinition>(0), collector, true);

    final long prepareTime = bolt.getCurrentTime();
    final MetricDefinition metricDefinition =
        subAlarms.get(0).getExpression().getMetricDefinition();
    final long oldestTimestamp = prepareTime - MetricFilteringBolt.LAG_MESSAGE_PERIOD_DEFAULT;
    final Tuple lateMetricTuple =
        createMetricTuple(metricDefinition, oldestTimestamp, new Metric(metricDefinition,
            oldestTimestamp, 42.0));
    bolt.execute(lateMetricTuple);
    verify(collector, times(1)).ack(lateMetricTuple);
    bolt.setCurrentTime(prepareTime + MetricFilteringBolt.LAG_MESSAGE_PERIOD_DEFAULT);
    final Tuple lateMetricTuple2 =
        createMetricTuple(metricDefinition, prepareTime, new Metric(metricDefinition, prepareTime,
            42.0));
    bolt.execute(lateMetricTuple2);
    verify(collector, times(1)).ack(lateMetricTuple2);
    verify(collector, times(1)).emit(MetricAggregationBolt.METRIC_AGGREGATION_CONTROL_STREAM,
        new Values(MetricAggregationBolt.METRICS_BEHIND));
    bolt.setCurrentTime(prepareTime + 2 * MetricFilteringBolt.LAG_MESSAGE_PERIOD_DEFAULT);
    long caughtUpTimestamp = bolt.getCurrentTime() - MetricFilteringBolt.MIN_LAG_VALUE_DEFAULT;
    final Tuple metricTuple =
        createMetricTuple(metricDefinition, caughtUpTimestamp, new Metric(metricDefinition,
            caughtUpTimestamp, 42.0));
    bolt.execute(metricTuple);
    // Metrics are caught up so there should not be another METRICS_BEHIND message
    verify(collector, times(1)).ack(metricTuple);
    verify(collector, times(1)).emit(MetricAggregationBolt.METRIC_AGGREGATION_CONTROL_STREAM,
        new Values(MetricAggregationBolt.METRICS_BEHIND));
  }

  public void testLaggingTooLong() {
    final OutputCollector collector = mock(OutputCollector.class);

    final MockMetricFilteringBolt bolt =
        createBolt(new ArrayList<SubAlarmMetricDefinition>(0), collector, true);

    long prepareTime = bolt.getCurrentTime();
    final MetricDefinition metricDefinition =
        subAlarms.get(0).getExpression().getMetricDefinition();
    // Fake sending metrics for MetricFilteringBolt.MAX_LAG_MESSAGES_DEFAULT *
    // MetricFilteringBolt.LAG_MESSAGE_PERIOD_DEFAULT seconds
    boolean first = true;
    // Need to send MetricFilteringBolt.MAX_LAG_MESSAGES_DEFAULT + 1 metrics because the lag message
    // is not
    // output on the first one.
    for (int i = 0; i < MetricFilteringBolt.MAX_LAG_MESSAGES_DEFAULT + 1; i++) {
      final Tuple lateMetricTuple =
          createMetricTuple(metricDefinition, prepareTime, new Metric(metricDefinition,
              prepareTime, 42.0));
      bolt.setCurrentTime(prepareTime + MetricFilteringBolt.LAG_MESSAGE_PERIOD_DEFAULT);
      bolt.execute(lateMetricTuple);
      verify(collector, times(1)).ack(lateMetricTuple);
      if (!first) {
        verify(collector, times(i)).emit(MetricAggregationBolt.METRIC_AGGREGATION_CONTROL_STREAM,
            new Values(MetricAggregationBolt.METRICS_BEHIND));
      }
      first = false;
      prepareTime = bolt.getCurrentTime();
    }
    // One more
    long timestamp = bolt.getCurrentTime() - MetricFilteringBolt.LAG_MESSAGE_PERIOD_DEFAULT;
    final Tuple metricTuple =
        createMetricTuple(metricDefinition, timestamp,
            new Metric(metricDefinition, timestamp, 42.0));
    bolt.execute(metricTuple);
    verify(collector, times(1)).ack(metricTuple);
    // Won't be any more of these
    verify(collector, times(MetricFilteringBolt.MAX_LAG_MESSAGES_DEFAULT)).emit(
        MetricAggregationBolt.METRIC_AGGREGATION_CONTROL_STREAM,
        new Values(MetricAggregationBolt.METRICS_BEHIND));
  }

  private static class MockMetricFilteringBolt extends MetricFilteringBolt {
    private static final long serialVersionUID = 1L;
    private long currentTimeMillis = System.currentTimeMillis();

    public MockMetricFilteringBolt(MetricDefinitionDAO metricDefDAO) {
      super(metricDefDAO);
    }

    @Override
    protected long getCurrentTime() {
      return currentTimeMillis;
    }

    public void setCurrentTime(final long currentTimeMillis) {
      this.currentTimeMillis = currentTimeMillis;
    }
  }

  public void testNoInitial() {
    MetricFilteringBolt.clearMetricDefinitions();
    final OutputCollector collector1 = mock(OutputCollector.class);

    final MetricFilteringBolt bolt1 =
        createBolt(new ArrayList<SubAlarmMetricDefinition>(0), collector1, true);

    final OutputCollector collector2 = mock(OutputCollector.class);

    final MetricFilteringBolt bolt2 =
        createBolt(new ArrayList<SubAlarmMetricDefinition>(0), collector2, false);

    // First ensure metrics don't pass the filter
    verifyMetricFiltered(collector1, bolt1);
    verifyMetricFiltered(collector2, bolt2);

    sendMetricCreation(collector1, bolt1);
    sendMetricCreation(collector2, bolt2);

    testDeleteSubAlarms(bolt1, collector1, bolt2, collector2);
  }

  private void sendMetricCreation(final OutputCollector collector1, final MetricFilteringBolt bolt1) {
    for (final SubAlarm subAlarm : subAlarms) {
      final Tuple tuple = createMetricDefinitionTuple(subAlarm);
      bolt1.execute(tuple);
      verify(collector1, times(1)).ack(tuple);
    }
  }

  private void verifyMetricFiltered(final OutputCollector collector1,
      final MetricFilteringBolt bolt1) {
    sendMetricsAndVerify(collector1, bolt1, never());
  }

  private void verifyMetricPassed(final OutputCollector collector1, final MetricFilteringBolt bolt1) {
    sendMetricsAndVerify(collector1, bolt1, times(1));
  }

  private void sendMetricsAndVerify(final OutputCollector collector1,
      final MetricFilteringBolt bolt1, VerificationMode howMany) {
    for (final SubAlarm subAlarm : subAlarms) {
      // First do a MetricDefinition that is an exact match
      final MetricDefinition metricDefinition = subAlarm.getExpression().getMetricDefinition();
      final Tuple exactTuple =
          createMetricTuple(metricDefinition, metricTimestamp++, new Metric(metricDefinition,
              metricTimestamp, 42.0));
      bolt1.execute(exactTuple);
      verify(collector1, times(1)).ack(exactTuple);
      verify(collector1, howMany).emit(new Values(exactTuple.getValue(0), exactTuple.getValue(2)));

      // Now do a MetricDefinition with an extra dimension that should still match the SubAlarm
      final Map<String, String> extraDimensions = new HashMap<>(metricDefinition.dimensions);
      extraDimensions.put("group", "group_a");
      final MetricDefinition inexactMetricDef =
          new MetricDefinition(metricDefinition.name, extraDimensions);
      Metric inexactMetric = new Metric(inexactMetricDef, metricTimestamp, 42.0);
      final Tuple inexactTuple =
          createMetricTuple(metricDefinition, metricTimestamp++, inexactMetric);
      bolt1.execute(inexactTuple);
      verify(collector1, times(1)).ack(inexactTuple);
      // We want the MetricDefinitionAndTenantId from the exact tuple, but the inexactMetric
      verify(collector1, howMany).emit(new Values(exactTuple.getValue(0), inexactMetric));
    }
  }

  public void testAllInitial() {
    MetricFilteringBolt.clearMetricDefinitions();
    final List<SubAlarmMetricDefinition> initialMetricDefinitions =
        new ArrayList<>(subAlarms.size());
    for (final SubAlarm subAlarm : subAlarms) {
      initialMetricDefinitions.add(new SubAlarmMetricDefinition(subAlarm.getId(),
          new MetricDefinitionAndTenantId(subAlarm.getExpression().getMetricDefinition(),
              TEST_TENANT_ID)));
    }
    final OutputCollector collector1 = mock(OutputCollector.class);

    final MetricFilteringBolt bolt1 = createBolt(initialMetricDefinitions, collector1, true);

    final OutputCollector collector2 = mock(OutputCollector.class);

    final MetricFilteringBolt bolt2 = createBolt(initialMetricDefinitions, collector2, false);

    testDeleteSubAlarms(bolt1, collector1, bolt2, collector2);
  }

  private void testDeleteSubAlarms(MetricFilteringBolt bolt1, OutputCollector collector1,
      MetricFilteringBolt bolt2, OutputCollector collector2) {
    // Now ensure metrics pass the filter
    verifyMetricPassed(collector1, bolt1);
    verifyMetricPassed(collector2, bolt2);

    // Now delete the SubAlarm that duplicated a MetricDefinition
    deleteSubAlarms(bolt1, collector1, duplicateMetricSubAlarms);
    deleteSubAlarms(bolt2, collector2, duplicateMetricSubAlarms);

    // Ensure metrics still pass the filter
    verifyMetricPassed(collector1, bolt1);
    verifyMetricPassed(collector2, bolt2);

    deleteSubAlarms(bolt1, collector1, subAlarms);
    // All MetricDefinitions should be deleted
    assertEquals(MetricFilteringBolt.sizeMetricDefinitions(), 0);
    deleteSubAlarms(bolt2, collector2, subAlarms);

    verifyMetricFiltered(collector1, bolt1);
    verifyMetricFiltered(collector2, bolt2);
  }

  private void deleteSubAlarms(MetricFilteringBolt bolt, OutputCollector collector,
      final List<SubAlarm> otherSubAlarms) {
    for (final SubAlarm subAlarm : otherSubAlarms) {
      final Tuple tuple = createMetricDefinitionDeletionTuple(subAlarm);
      bolt.execute(tuple);
      verify(collector, times(1)).ack(tuple);
    }
  }

  private Tuple createMetricDefinitionTuple(final SubAlarm subAlarm) {
    final MkTupleParam tupleParam = new MkTupleParam();
    tupleParam.setFields(EventProcessingBolt.METRIC_SUB_ALARM_EVENT_STREAM_FIELDS);
    tupleParam.setStream(EventProcessingBolt.METRIC_SUB_ALARM_EVENT_STREAM_ID);
    final Tuple tuple =
        Testing.testTuple(Arrays.asList(EventProcessingBolt.CREATED,
            new MetricDefinitionAndTenantId(subAlarm.getExpression().getMetricDefinition(),
                TEST_TENANT_ID), subAlarm), tupleParam);
    return tuple;
  }

  private Tuple createMetricDefinitionDeletionTuple(final SubAlarm subAlarm) {
    final MkTupleParam tupleParam = new MkTupleParam();
    tupleParam.setFields(EventProcessingBolt.METRIC_ALARM_EVENT_STREAM_FIELDS);
    tupleParam.setStream(EventProcessingBolt.METRIC_ALARM_EVENT_STREAM_ID);
    final Tuple tuple =
        Testing.testTuple(Arrays.asList(EventProcessingBolt.DELETED,
            new MetricDefinitionAndTenantId(subAlarm.getExpression().getMetricDefinition(),
                TEST_TENANT_ID), subAlarm.getId()), tupleParam);

    return tuple;
  }

  private Tuple createMetricTuple(final MetricDefinition metricDefinition, final long timestamp,
      final Metric metric) {
    final MkTupleParam tupleParam = new MkTupleParam();
    tupleParam.setFields(MetricSpout.FIELDS);
    tupleParam.setStream(Streams.DEFAULT_STREAM_ID);
    final Tuple tuple =
        Testing.testTuple(Arrays.asList(new MetricDefinitionAndTenantId(metricDefinition,
            TEST_TENANT_ID), timestamp, metric), tupleParam);
    return tuple;
  }
}