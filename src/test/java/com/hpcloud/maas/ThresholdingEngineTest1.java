package com.hpcloud.maas;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.testng.annotations.Test;

import backtype.storm.Config;
import backtype.storm.testing.FeederSpout;
import backtype.storm.tuple.Fields;
import backtype.storm.tuple.Values;

import com.google.common.collect.ImmutableMap;
import com.google.inject.AbstractModule;
import com.hpcloud.maas.common.event.AlarmCreatedEvent;
import com.hpcloud.maas.common.event.AlarmDeletedEvent;
import com.hpcloud.maas.common.model.alarm.AlarmExpression;
import com.hpcloud.maas.common.model.alarm.AlarmState;
import com.hpcloud.maas.common.model.alarm.AlarmSubExpression;
import com.hpcloud.maas.common.model.metric.Metric;
import com.hpcloud.maas.common.model.metric.MetricDefinition;
import com.hpcloud.maas.domain.model.Alarm;
import com.hpcloud.maas.domain.model.SubAlarm;
import com.hpcloud.maas.domain.service.AlarmDAO;
import com.hpcloud.maas.domain.service.MetricDefinitionDAO;
import com.hpcloud.maas.domain.service.SubAlarmDAO;
import com.hpcloud.maas.infrastructure.storm.TopologyTestCase;
import com.hpcloud.maas.infrastructure.thresholding.MetricAggregationBolt;
import com.hpcloud.messaging.rabbitmq.RabbitMQService;
import com.hpcloud.util.Injector;

/**
 * Simulates a real'ish run of the thresholding engine, using seconds instead of minutes for the
 * evaluation timescale.
 * 
 * @author Jonathan Halterman
 */
@Test(groups = "integration")
public class ThresholdingEngineTest1 extends TopologyTestCase {
  private FeederSpout collectdMetricSpout;
  private FeederSpout maasMetricSpout;
  private FeederSpout eventSpout;
  private AlarmDAO alarmDAO;
  private SubAlarmDAO subAlarmDAO;
  private MetricDefinition cpuMetricDef;
  private MetricDefinition memMetricDef;
  private MetricDefinition customMetricDef;
  private MetricDefinitionDAO metricDefinitionDAO;

  private AlarmExpression expression;
  private AlarmExpression customExpression;
  private AlarmSubExpression customSubExpression;

  public ThresholdingEngineTest1() {
    // Fixtures
    expression = new AlarmExpression(
        "avg(compute:cpu:{id=5}, 3) >= 3 times 2 and avg(compute:mem:{id=5}, 3) >= 5 times 2");
    customExpression = AlarmExpression.of("avg(my:test:{id=4}, 3) > 10");
    customSubExpression = customExpression.getSubExpressions().get(0);

    cpuMetricDef = expression.getSubExpressions().get(0).getMetricDefinition();
    memMetricDef = expression.getSubExpressions().get(1).getMetricDefinition();
    customMetricDef = customSubExpression.getMetricDefinition();

    // Mocks
    alarmDAO = mock(AlarmDAO.class);
    when(alarmDAO.findById(anyString())).thenAnswer(new Answer<Alarm>() {
      @Override
      public Alarm answer(InvocationOnMock invocation) throws Throwable {
        if (invocation.getArguments()[0].equals("1"))
          return new Alarm("1", "bob", "test-alarm", expression, Arrays.asList(createCpuSubAlarm(),
              createMemSubAlarm()), AlarmState.OK);
        else if (invocation.getArguments()[0].equals("2"))
          return new Alarm("2", "joe", "joes-alarm", customExpression,
              Arrays.asList(createCustomSubAlarm()), AlarmState.OK);
        return null;
      }
    });

    subAlarmDAO = mock(SubAlarmDAO.class);
    when(subAlarmDAO.find(any(MetricDefinition.class))).thenAnswer(new Answer<List<SubAlarm>>() {
      @Override
      public List<SubAlarm> answer(InvocationOnMock invocation) throws Throwable {
        MetricDefinition metricDef = (MetricDefinition) invocation.getArguments()[0];
        if (metricDef.equals(cpuMetricDef))
          return Arrays.asList(createCpuSubAlarm());
        else if (metricDef.equals(memMetricDef))
          return Arrays.asList(createMemSubAlarm());
        else if (metricDef.equals(customMetricDef))
          return Arrays.asList(createCustomSubAlarm());
        return Collections.emptyList();
      }
    });

    metricDefinitionDAO = mock(MetricDefinitionDAO.class);
    List<MetricDefinition> metricDefs = Arrays.asList(cpuMetricDef, memMetricDef, customMetricDef);
    when(metricDefinitionDAO.findForAlarms()).thenReturn(metricDefs);

    final RabbitMQService rabbitMQService = mock(RabbitMQService.class);

    // Bindings
    Injector.reset();
    Injector.registerModules(new AbstractModule() {
      protected void configure() {
        bind(AlarmDAO.class).toInstance(alarmDAO);
        bind(SubAlarmDAO.class).toInstance(subAlarmDAO);
        bind(MetricDefinitionDAO.class).toInstance(metricDefinitionDAO);
        bind(RabbitMQService.class).toInstance(rabbitMQService);
      }
    });

    // Config
    ThresholdingConfiguration threshConfig = new ThresholdingConfiguration();
    Config stormConfig = new Config();
    stormConfig.setMaxTaskParallelism(5);

    collectdMetricSpout = new FeederSpout(new Fields("metricDefinition", "metric"));
    maasMetricSpout = new FeederSpout(new Fields("metricDefinition", "metric"));
    eventSpout = new FeederSpout(new Fields("event"));
    Injector.registerModules(new TopologyModule(threshConfig, stormConfig, collectdMetricSpout,
        maasMetricSpout, eventSpout));

    // Evaluate alarm stats every 1 seconds
    System.setProperty(MetricAggregationBolt.TICK_TUPLE_SECONDS_KEY, "1");
  }

  private SubAlarm createCpuSubAlarm() {
    return new SubAlarm("111", "1", expression.getSubExpressions().get(0));
  }

  private SubAlarm createMemSubAlarm() {
    return new SubAlarm("222", "1", expression.getSubExpressions().get(1));
  }

  private SubAlarm createCustomSubAlarm() {
    return new SubAlarm("333", "2", customSubExpression);
  }

  public void shouldThreshold() throws Exception {
    int count = 0;
    int eventCounter = 0;

    while (true) {
      long time = System.currentTimeMillis();
      collectdMetricSpout.feed(new Values(cpuMetricDef, new Metric(cpuMetricDef, time,
          count % 10 == 0 ? 555 : 1)));
      collectdMetricSpout.feed(new Values(memMetricDef, new Metric(memMetricDef, time,
          count % 10 == 0 ? 555 : 1)));
      maasMetricSpout.feed(new Values(customMetricDef, new Metric(customMetricDef, time,
          count % 20 == 0 ? 1 : 123)));

      if (count % 5 == 0) {
        Object event = null;
        if (++eventCounter % 2 == 0)
          event = new AlarmDeletedEvent("joe", "2",
              ImmutableMap.<String, MetricDefinition>builder().put("444", customMetricDef).build());
        else
          event = new AlarmCreatedEvent("joe", "2", "foo", customSubExpression.getExpression(),
              ImmutableMap.<String, AlarmSubExpression>builder()
                  .put("444", customSubExpression)
                  .build());

        eventSpout.feed(new Values(event));
      }

      try {
        Thread.sleep(1000);
      } catch (InterruptedException e) {
        return;
      }

      count++;
    }
  }
}