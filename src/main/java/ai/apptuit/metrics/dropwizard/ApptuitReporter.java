/*
 * Copyright 2017 Agilx, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.apptuit.metrics.dropwizard;

import ai.apptuit.metrics.client.ApptuitPutClient;
import ai.apptuit.metrics.client.DataPoint;
import ai.apptuit.metrics.client.Sanitizer;
import ai.apptuit.metrics.client.XCollectorForwarder;
import com.codahale.metrics.*;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URL;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author Rajiv Shivane
 */
public class ApptuitReporter extends ScheduledReporter {

  private static final Logger LOGGER = Logger.getLogger(ApptuitReporter.class.getName());
  private static final boolean DEBUG = false;
  private static final ReportingMode DEFAULT_REPORTING_MODE = ReportingMode.API_PUT;
  private static final String REPORTER_NAME = "apptuit-reporter";

  private final Timer buildReportTimer;
  private final Timer sendReportTimer;
  private final Counter metricsSentCounter;
  private final Counter pointsSentCounter;
  private final DataPointsReporter dataPointsReporter;
  private final Map<TagEncodedMetricName, Long> lastReportedCount = new HashMap<>();
  private final ReportingMode reportingMode;

  protected ApptuitReporter(MetricRegistry registry, MetricFilter filter, TimeUnit rateUnit,
                            TimeUnit durationUnit, Map<String, String> globalTags,
                            String key, URL apiUrl,
                            ReportingMode reportingMode, Sanitizer sanitizer) {
    super(registry, REPORTER_NAME, filter, rateUnit, durationUnit);

    this.buildReportTimer = registry.timer("apptuit.reporter.report.build");
    this.sendReportTimer = registry.timer("apptuit.reporter.report.send");
    this.metricsSentCounter = registry.counter("apptuit.reporter.metrics.sent.count");
    this.pointsSentCounter = registry.counter("apptuit.reporter.points.sent.count");


    if (reportingMode == null) {
      this.reportingMode = DEFAULT_REPORTING_MODE;
    } else {
      this.reportingMode = reportingMode;
    }

    switch (this.reportingMode) {
      case NO_OP:
        this.dataPointsReporter = dataPoints -> {
        };
        break;
      case SYS_OUT:
        this.dataPointsReporter = dataPoints -> {
          dataPoints.forEach(dp -> dp.toTextLine(System.out, globalTags, sanitizer));
        };
        break;
      case XCOLLECTOR:
        XCollectorForwarder forwarder = new XCollectorForwarder(globalTags);
        this.dataPointsReporter = dataPoints -> forwarder.forward(dataPoints, sanitizer);
        break;
      case API_PUT:
      default:
        ApptuitPutClient putClient = new ApptuitPutClient(key, globalTags, apiUrl);
        this.dataPointsReporter = dataPoints -> putClient.put(dataPoints, sanitizer);
        break;
    }
  }

  @Override
  public void report(SortedMap<String, Gauge> gauges, SortedMap<String, Counter> counters,
                     SortedMap<String, Histogram> histograms, SortedMap<String, Meter> meters,
                     SortedMap<String, Timer> timers) {

    DataPointCollector collector = new DataPointCollector(System.currentTimeMillis() / 1000);
    try {
      buildReportTimer.time(new Callable<Object>() {
        @Override
        public Object call() {
          debug("################");

          debug(">>>>>>>> Guages <<<<<<<<<");
          gauges.forEach(collector::collectGauge);
          debug(">>>>>>>> Counters <<<<<<<<<");
          counters.forEach(collector::collectCounter);
          debug(">>>>>>>> Histograms <<<<<<<<<");
          histograms.forEach(collector::collectHistogram);
          debug(">>>>>>>> Meters <<<<<<<<<");
          meters.forEach(collector::collectMeter);
          debug(">>>>>>>> Timers <<<<<<<<<");
          timers.forEach(collector::collectTimer);

          debug("################");
          int numMetrics = gauges.size() + counters.size() + histograms.size() + meters.size() + timers.size();
          metricsSentCounter.inc(numMetrics);
          pointsSentCounter.inc(collector.dataPoints.size());
          return null;
        }
      });
    } catch (Exception | Error e) {
      LOGGER.log(Level.SEVERE, "Error building metrics.", e);
    }

    try {
      sendReportTimer.time(new Callable<Object>() {
        @Override
        public Object call() {
          Collection<DataPoint> dataPoints = collector.dataPoints;
          dataPointsReporter.put(dataPoints);
          //dataPoints.forEach(System.out::println);
          return null;
        }
      });
    } catch (Exception | Error e) {
      LOGGER.log(Level.SEVERE, "Error reporting metrics.", e);
    }

  }

  private void debug(Object s) {
    if (DEBUG) {
      System.out.println(s);
    }
  }

  public enum ReportingMode {
    NO_OP, SYS_OUT, XCOLLECTOR, API_PUT
  }

  public interface DataPointsReporter {

    void put(Collection<DataPoint> dataPoints);
  }

  private class DataPointCollector {

    private final long epoch;
    private final List<DataPoint> dataPoints;

    DataPointCollector(long epoch) {
      this.epoch = epoch;
      this.dataPoints = new LinkedList<>();
    }

    private void collectGauge(String name, Gauge gauge) {
      Object value = gauge.getValue();
      if (value instanceof BigDecimal) {
        addDataPoint(name, ((BigDecimal) value).doubleValue());
      } else if (value instanceof BigInteger) {
        addDataPoint(name, ((BigInteger) value).doubleValue());
      } else if (value != null && value.getClass().isAssignableFrom(Double.class)) {
        if (!Double.isNaN((Double) value) && Double.isFinite((Double) value)) {
          addDataPoint(name, (Double) value);
        }
      } else if (value instanceof Number) {
        addDataPoint(name, ((Number) value).doubleValue());
      }
    }

    private void collectCounter(String name, Counter counter) {
      addDataPoint(name, counter.getCount());
    }


    private void collectHistogram(String name, Histogram histogram) {
      TagEncodedMetricName rootMetric = TagEncodedMetricName.decode(name);
      collectCounting(rootMetric, histogram, () -> reportSnapshot(rootMetric, histogram));
    }

    private void collectMeter(String name, Meter meter) {
      TagEncodedMetricName rootMetric = TagEncodedMetricName.decode(name);
      collectCounting(rootMetric, meter, () -> reportMetered(rootMetric, meter));
    }

    private void collectTimer(String name, final Timer timer) {
      TagEncodedMetricName rootMetric = TagEncodedMetricName.decode(name);
      collectCounting(rootMetric, timer, () -> {
        reportSnapshot(rootMetric, timer);
        reportMetered(rootMetric, timer)
        ;
      });
    }


    private <T extends Counting> void collectCounting(TagEncodedMetricName rootMetric, T metric,
                                                      Runnable reportSubmetrics) {
      long currentCount = metric.getCount();
      if (metric.getClass() == Meter.class) {
        addDataPoint(rootMetric.submetric("total"), currentCount);
      } else {
        addDataPoint(rootMetric.submetric("count"), currentCount);
      }
      Long lastCount = lastReportedCount.put(rootMetric, currentCount);
      if (lastCount == null || lastCount != currentCount) {
        reportSubmetrics.run();
      }
    }

    private void reportSnapshot(TagEncodedMetricName metric, Sampling samplingObj) {
      Snapshot snapshot = samplingObj.getSnapshot();
      String additionalSubMetric = "";
      if (samplingObj.getClass() == Timer.class) {
        additionalSubMetric = "duration";
      }
      addDataPoint(metric.submetric(additionalSubMetric).submetric("min"), convertDuration(snapshot.getMin()));
      addDataPoint(metric.submetric(additionalSubMetric).submetric("max"), convertDuration(snapshot.getMax()));
      addDataPoint(metric.submetric(additionalSubMetric).submetric("mean"), convertDuration(snapshot.getMean()));
      addDataPoint(metric.submetric(additionalSubMetric).submetric("stddev"), convertDuration(snapshot.getStdDev()));
      addDataPoint(metric.submetric(additionalSubMetric).withTags("quantile", "0.5"),
              convertDuration(snapshot.getMedian()));
      addDataPoint(metric.submetric(additionalSubMetric).withTags("quantile", "0.75"),
              convertDuration(snapshot.get75thPercentile()));
      addDataPoint(metric.submetric(additionalSubMetric).withTags("quantile", "0.95"),
              convertDuration(snapshot.get95thPercentile()));
      addDataPoint(metric.submetric(additionalSubMetric).withTags("quantile", "0.98"),
              convertDuration(snapshot.get98thPercentile()));
      addDataPoint(metric.submetric(additionalSubMetric).withTags("quantile", "0.99"),
              convertDuration(snapshot.get99thPercentile()));
      addDataPoint(metric.submetric(additionalSubMetric).withTags("quantile", "0.999"),
              convertDuration(snapshot.get999thPercentile()));
    }

    private void reportMetered(TagEncodedMetricName metric, Metered meter) {
      addDataPoint(metric.submetric("rate").withTags("window", "1m"),
              convertRate(meter.getOneMinuteRate()));
      addDataPoint(metric.submetric("rate").withTags("window", "5m"),
              convertRate(meter.getFiveMinuteRate()));
      addDataPoint(metric.submetric("rate").withTags("window", "15m"),
              convertRate(meter.getFifteenMinuteRate()));
      //addDataPoint(rootMetric.submetric("rate", "window", "all"), epoch, meter.getMeanRate());
    }

    private double convertRate(double rate) {
      return ApptuitReporter.this.convertRate(rate);
    }

    private double convertDuration(double duration) {
      return ApptuitReporter.this.convertDuration(duration);
    }

    private void addDataPoint(String name, double value) {
      addDataPoint(TagEncodedMetricName.decode(name), value);
    }

    private void addDataPoint(String name, long value) {
      addDataPoint(TagEncodedMetricName.decode(name), value);
    }

    private void addDataPoint(TagEncodedMetricName name, Number value) {
      /*
      //TODO support disabled metric attributes
      if(getDisabledMetricAttributes().contains(type)) {
          return;
      }
      */

      DataPoint dataPoint = new DataPoint(name.getMetricName(), epoch, value, name.getTags());
      dataPoints.add(dataPoint);
      debug(dataPoint);
    }
  }
}
