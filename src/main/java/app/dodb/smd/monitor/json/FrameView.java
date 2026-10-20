package app.dodb.smd.monitor.json;

import app.dodb.smd.monitor.hub.Counts;
import app.dodb.smd.monitor.hub.HubStatistics;
import app.dodb.smd.monitor.hub.Instance;
import app.dodb.smd.monitor.hub.LatencyHistogram;
import app.dodb.smd.monitor.hub.health.HealthView;
import app.dodb.smd.monitor.hub.span.MessageType;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

// What a page receives every second: the statistics of every application, whole, and the sequence number of what the hub retains, which
// moves whenever the hub retains another invocation or finds another parent; pages query the retained invocations themselves. Per-second
// figures end at the end second, settle seconds before the hub's clock, and those that cover the window or the history span its seconds. The maximum heap of the hub is null when the JVM sets none.
// The history is the one the counts cover, shorter than configured under memory pressure. An instance that sent no spans for the retire-after seconds has left the instances of its application.
public record FrameView(String hubSession,
                        double capturedAtMillis,
                        long sequence,
                        long endEpochSecond,
                        Long maxHeapBytes,
                        long windowSeconds,
                        long historySeconds,
                        long settleSeconds,
                        long retireAfterSeconds,
                        HubHealthView health,
                        List<ApplicationView> applications) {

    private static final MathContext FIGURE = new MathContext(3);

    public static FrameView from(Instant capturedAt, HubStatistics statistics, HealthView health, OptionalLong maxHeapBytes) {
        return new FrameView(statistics.session().toString(), epochMillis(capturedAt), statistics.sequence(), statistics.end().getEpochSecond(),
            maxHeapBytes.isPresent() ? maxHeapBytes.getAsLong() : null, statistics.window().toSeconds(), statistics.history().toSeconds(), statistics.settle().toSeconds(),
            statistics.retireAfter().toSeconds(), HubHealthView.from(health),
            statistics.applications().stream().map(ApplicationView::from).toList());
    }

    static double epochMillis(Instant instant) {
        return instant.getEpochSecond() * 1_000.0 + instant.getNano() / 1_000_000.0;
    }

    static Double millis(Optional<Duration> duration) {
        return duration.map(value -> value.toNanos() / 1_000_000.0).orElse(null);
    }

    static Double p95Millis(LatencyHistogram latency) {
        return figure(latency.percentile(0.95));
    }

    // A latency figure of the frame in milliseconds, to the three significant digits the page shows, so a change it would not show does not
    // change the frame. The nanoseconds round half up as integers, as an exact decimal of every figure took most of a frame at many
    // applications; one exactly halfway rounds its milliseconds as a decimal, as their double may lie on either side of it.
    static Double figure(Optional<Duration> duration) {
        return duration.map(FrameView::figure).orElse(null);
    }

    private static double figure(Duration duration) {
        var nanos = duration.toNanos();
        var scale = 1L;
        while (nanos / scale >= 1_000) {
            scale *= 10;
        }
        var remainder = nanos % scale;
        if (2 * remainder == scale) {
            return new BigDecimal(nanos / 1_000_000.0).round(FIGURE).doubleValue();
        }
        var rounded = (nanos / scale + (2 * remainder > scale ? 1 : 0)) * scale;
        return rounded / 1_000_000.0;
    }

    // The export lag is the p99 of how long its recent spans took to reach the hub after they ended, null while none arrived recently.
    public record InstanceView(String name, String sessionId, double lastReportedAtMillis, boolean silent, boolean clockAhead, Double exportLagMillis,
                               BriefView dispatches, BriefView handlings) {

        static InstanceView from(Instance instance) {
            return new InstanceView(instance.name().value(), instance.session().value(), epochMillis(instance.lastReportedAt()), instance.silent(),
                instance.clockAhead(), figure(instance.exportLag()), BriefView.from(instance.dispatches()), BriefView.from(instance.handlings()));
        }
    }

    public record MessageTypeView(String kind, String name) {

        static MessageTypeView from(MessageType type) {
            return new MessageTypeView(type.kind().name(), type.name());
        }
    }

    // The latency figures are null when nothing completed, and have three significant digits. Statistics are written as an array of their
    // fields in this order, which a change replaces whole: the frame carries thousands and they change every second.
    @JsonFormat(shape = JsonFormat.Shape.ARRAY)
    public record StatisticsView(long started, long entered, long failed, long completed, Double averageMillis, Double floorMillis,
                                 Double p50Millis, Double p95Millis, Double p99Millis, Double maxMillis) {

        static StatisticsView from(Counts counts) {
            var latency = counts.latency();
            var completed = latency.count();
            return new StatisticsView(counts.started(), counts.entered(), counts.failed(), completed, figure(latency.average()),
                figure(latency.floor()), figure(latency.percentile(0.5)), figure(latency.percentile(0.95)), figure(latency.percentile(0.99)),
                figure(completed == 0 ? Optional.empty() : Optional.of(latency.max())));
        }
    }

    // The counts and the p95 of a key whose other percentiles no view shows, written as an array like StatisticsView.
    @JsonFormat(shape = JsonFormat.Shape.ARRAY)
    public record BriefView(long started, long entered, long failed, long completed, Double p95Millis) {

        static BriefView from(Counts counts) {
            var latency = counts.latency();
            return new BriefView(counts.started(), counts.entered(), counts.failed(), latency.count(), figure(latency.percentile(0.95)));
        }
    }
}
