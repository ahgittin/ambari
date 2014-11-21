/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.yarn.server.applicationhistoryservice.metrics.timeline;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.metrics2.sink.timeline.TimelineMetric;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.apache.hadoop.yarn.server.applicationhistoryservice.metrics.timeline.PhoenixTransactSQL.Condition;
import static org.apache.hadoop.yarn.server.applicationhistoryservice.metrics.timeline.PhoenixTransactSQL.GET_METRIC_SQL;
import static org.apache.hadoop.yarn.server.applicationhistoryservice.metrics.timeline.PhoenixTransactSQL.METRICS_RECORD_TABLE_NAME;
import static org.apache.hadoop.yarn.server.applicationhistoryservice.metrics.timeline.PhoenixTransactSQL.prepareGetMetricsSqlStmt;
import static org.apache.hadoop.yarn.server.applicationhistoryservice.metrics.timeline.TimelineMetricConfiguration.*;
import static org.apache.hadoop.yarn.server.applicationhistoryservice.metrics.timeline.TimelineMetricConfiguration.CLUSTER_AGGREGATOR_MINUTE_CHECKPOINT_CUTOFF_MULTIPLIER;
import static org.apache.hadoop.yarn.server.applicationhistoryservice.metrics.timeline.TimelineMetricConfiguration.CLUSTER_AGGREGATOR_MINUTE_SLEEP_INTERVAL;
import static org.apache.hadoop.yarn.server.applicationhistoryservice.metrics.timeline.TimelineMetricConfiguration.CLUSTER_AGGREGATOR_TIMESLICE_INTERVAL;
import static org.apache.hadoop.yarn.server.applicationhistoryservice.metrics.timeline.TimelineMetricConfiguration.DEFAULT_CHECKPOINT_LOCATION;
import static org.apache.hadoop.yarn.server.applicationhistoryservice.metrics.timeline.TimelineMetricConfiguration.TIMELINE_METRICS_AGGREGATOR_CHECKPOINT_DIR;

/**
 * Aggregates a metric across all hosts in the cluster. Reads metrics from
 * the precision table and saves into the aggregate.
 */
public class TimelineMetricClusterAggregator extends AbstractTimelineAggregator {
  private static final Log LOG = LogFactory.getLog(TimelineMetricClusterAggregator.class);
  private static final String CLUSTER_AGGREGATOR_CHECKPOINT_FILE =
    "timeline-metrics-cluster-aggregator-checkpoint";
  private final String checkpointLocation;
  private final Long sleepIntervalMillis;
  public final int timeSliceIntervalMillis;
  private final Integer checkpointCutOffMultiplier;

  public TimelineMetricClusterAggregator(PhoenixHBaseAccessor hBaseAccessor,
                                         Configuration metricsConf) {
    super(hBaseAccessor, metricsConf);

    String checkpointDir = metricsConf.get(
      TIMELINE_METRICS_AGGREGATOR_CHECKPOINT_DIR, DEFAULT_CHECKPOINT_LOCATION);

    checkpointLocation = FilenameUtils.concat(checkpointDir,
      CLUSTER_AGGREGATOR_CHECKPOINT_FILE);

    sleepIntervalMillis = SECONDS.toMillis(metricsConf.getLong
      (CLUSTER_AGGREGATOR_MINUTE_SLEEP_INTERVAL, 120l));
    timeSliceIntervalMillis = (int)SECONDS.toMillis(metricsConf.getInt
      (CLUSTER_AGGREGATOR_TIMESLICE_INTERVAL, 15));
    checkpointCutOffMultiplier =
      metricsConf.getInt(CLUSTER_AGGREGATOR_MINUTE_CHECKPOINT_CUTOFF_MULTIPLIER, 2);
  }

  @Override
  protected String getCheckpointLocation() {
    return checkpointLocation;
  }

  /**
   * Read metrics written during the time interval and save the sum and total
   * in the aggregate table.
   *
   * @param startTime Sample start time
   * @param endTime Sample end time
   */
  protected boolean doWork(long startTime, long endTime) {
    LOG.info("Start aggregation cycle @ " + new Date() + ", " +
      "startTime = " + new Date(startTime) + ", endTime = " + new Date(endTime));

    boolean success = true;
    Condition condition = prepareMetricQueryCondition(startTime, endTime);

    Connection conn;
    PreparedStatement stmt;

    try {
      conn = hBaseAccessor.getConnection();
      stmt = prepareGetMetricsSqlStmt(conn, condition);
      LOG.debug("Query issued @: " + new Date());
      ResultSet rs = stmt.executeQuery();
      LOG.debug("Query returned @: " + new Date());
      Map<TimelineClusterMetric, MetricClusterAggregate> aggregateClusterMetrics =
        new HashMap<TimelineClusterMetric, MetricClusterAggregate>();
      List<Long[]> timeSlices = new ArrayList<Long[]>();
      // Create time slices
      long sliceStartTime = startTime;
      while (sliceStartTime < endTime) {
        timeSlices.add(new Long[] { sliceStartTime, sliceStartTime + timeSliceIntervalMillis});
        sliceStartTime += timeSliceIntervalMillis;
      }

      while (rs.next()) {
        TimelineMetric metric =
          PhoenixHBaseAccessor.getTimelineMetricFromResultSet(rs);

        Map<TimelineClusterMetric, Double> clusterMetrics =
          sliceFromTimelineMetric(metric, timeSlices);

        if (clusterMetrics != null && !clusterMetrics.isEmpty()) {
          for (Map.Entry<TimelineClusterMetric, Double> clusterMetricEntry :
              clusterMetrics.entrySet()) {
            TimelineClusterMetric clusterMetric = clusterMetricEntry.getKey();
            MetricClusterAggregate aggregate = aggregateClusterMetrics.get(clusterMetric);
            Double avgValue = clusterMetricEntry.getValue();

            if (aggregate == null) {
              aggregate = new MetricClusterAggregate(avgValue, 1, null,
                avgValue, avgValue);
              aggregateClusterMetrics.put(clusterMetric, aggregate);
            } else {
              aggregate.updateSum(avgValue);
              aggregate.updateNumberOfHosts(1);
              aggregate.updateMax(avgValue);
              aggregate.updateMin(avgValue);
            }
          }
        }
      }
      LOG.info("Saving " + aggregateClusterMetrics.size() + " metric aggregates.");

      hBaseAccessor.saveClusterAggregateRecords(aggregateClusterMetrics);

      LOG.info("End aggregation cycle @ " + new Date());

    } catch (SQLException e) {
      LOG.error("Exception during aggregating metrics.", e);
      success = false;
    } catch (IOException e) {
      LOG.error("Exception during aggregating metrics.", e);
      success = false;
    }

    return success;
  }

  private Condition prepareMetricQueryCondition(long startTime, long endTime) {
    Condition condition = new Condition(null, null, null, null, startTime,
                                        endTime, null, true);
    condition.setFetchSize(resultsetFetchSize);
    condition.setNoLimit();
    condition.setStatement(String.format(GET_METRIC_SQL,
      METRICS_RECORD_TABLE_NAME));
    condition.addOrderByColumn("METRIC_NAME");
    condition.addOrderByColumn("APP_ID");
    condition.addOrderByColumn("INSTANCE_ID");
    condition.addOrderByColumn("SERVER_TIME");

    return condition;
  }

  @Override
  protected Long getSleepIntervalMillis() {
    return sleepIntervalMillis;
  }

  @Override
  protected Integer getCheckpointCutOffMultiplier() {
    return checkpointCutOffMultiplier;
  }

  @Override
  protected boolean isDisabled() {
    return metricsConf.getBoolean(CLUSTER_AGGREGATOR_MINUTE_DISABLED, false);
  }

  private Map<TimelineClusterMetric, Double> sliceFromTimelineMetric(
        TimelineMetric timelineMetric, List<Long[]> timeSlices) {

    if (timelineMetric.getMetricValues().isEmpty()) {
      return null;
    }

    Map<TimelineClusterMetric, Double> timelineClusterMetricMap =
      new HashMap<TimelineClusterMetric, Double>();

    for (Map.Entry<Long, Double> metric : timelineMetric.getMetricValues().entrySet()) {
      // TODO: investigate null values - pre filter
      if (metric.getValue() == null) {
        continue;
      }
      Long timestamp = getSliceTimeForMetric(timeSlices,
                       Long.parseLong(metric.getKey().toString()));
      if (timestamp != -1) {
        // Metric is within desired time range
        TimelineClusterMetric clusterMetric = new TimelineClusterMetric(
          timelineMetric.getMetricName(),
          timelineMetric.getAppId(),
          timelineMetric.getInstanceId(),
          timestamp,
          timelineMetric.getType());
        if (!timelineClusterMetricMap.containsKey(clusterMetric)) {
          timelineClusterMetricMap.put(clusterMetric, metric.getValue());
        } else {
          Double oldValue = timelineClusterMetricMap.get(clusterMetric);
          Double newValue = (oldValue + metric.getValue()) / 2;
          timelineClusterMetricMap.put(clusterMetric, newValue);
        }
      }
    }

    return timelineClusterMetricMap;
  }

  /**
   * Return beginning of the time slice into which the metric fits.
   */
  private Long getSliceTimeForMetric(List<Long[]> timeSlices, Long timestamp) {
    for (Long[] timeSlice : timeSlices) {
      if (timestamp >= timeSlice[0] && timestamp < timeSlice[1]) {
        return timeSlice[0];
      }
    }
    return -1l;
  }

  public static class TimelineClusterMetric {
    private String metricName;
    private String appId;
    private String instanceId;
    private long timestamp;
    private String type;

    TimelineClusterMetric(String metricName, String appId, String instanceId,
                          long timestamp, String type) {
      this.metricName = metricName;
      this.appId = appId;
      this.instanceId = instanceId;
      this.timestamp = timestamp;
      this.type = type;
    }

    String getMetricName() {
      return metricName;
    }

    String getAppId() {
      return appId;
    }

    String getInstanceId() {
      return instanceId;
    }

    long getTimestamp() {
      return timestamp;
    }

    String getType() { return type; }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;

      TimelineClusterMetric that = (TimelineClusterMetric) o;

      if (timestamp != that.timestamp) return false;
      if (appId != null ? !appId.equals(that.appId) : that.appId != null)
        return false;
      if (instanceId != null ? !instanceId.equals(that.instanceId) : that.instanceId != null)
        return false;
      if (!metricName.equals(that.metricName)) return false;

      return true;
    }

    public boolean equalsExceptTime(TimelineClusterMetric metric) {
      if (!metricName.equals(metric.metricName)) return false;
      if (!appId.equals(metric.appId)) return false;
      if (instanceId != null ? !instanceId.equals(metric.instanceId) : metric.instanceId != null)
        return false;

      return true;
    }
    @Override
    public int hashCode() {
      int result = metricName.hashCode();
      result = 31 * result + (appId != null ? appId.hashCode() : 0);
      result = 31 * result + (instanceId != null ? instanceId.hashCode() : 0);
      result = 31 * result + (int) (timestamp ^ (timestamp >>> 32));
      return result;
    }

    @Override
    public String toString() {
      return "TimelineClusterMetric{" +
        "metricName='" + metricName + '\'' +
        ", appId='" + appId + '\'' +
        ", instanceId='" + instanceId + '\'' +
        ", timestamp=" + timestamp +
        '}';
    }
  }
}