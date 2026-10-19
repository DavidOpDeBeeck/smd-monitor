package app.dodb.smd.monitor.hub;

import app.dodb.smd.monitor.hub.health.HealthReason;
import app.dodb.smd.monitor.hub.intake.ReportingInstance;
import app.dodb.smd.monitor.hub.intake.Shedding;
import app.dodb.smd.monitor.hub.join.Link;
import app.dodb.smd.monitor.hub.span.ApplicationName;
import app.dodb.smd.monitor.hub.span.InstanceName;
import app.dodb.smd.monitor.hub.span.MessageSpan;
import app.dodb.smd.monitor.hub.span.Outcome;
import app.dodb.smd.monitor.hub.span.SinkSend;
import app.dodb.smd.monitor.hub.span.Stage;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

import static java.util.Objects.requireNonNull;

// What the hub counts of an application's spans; how many arrived again, failed, or were shed, and in failed batches; and the heaviest
// shedding of its batches since the last tick. What it retains of them the specimens hold.
class ApplicationMonitor {

    private final ApplicationName name;
    private final ApplicationCounting counting;
    private long duplicates;
    private long failedReceived;
    private long shedUnretained;
    private long shedUnjoined;
    private long failedBatches;
    private long shedThroughNanos = Long.MIN_VALUE;
    private Shedding shedding = Shedding.NONE;

    ApplicationMonitor(ApplicationName name, HubConfig config, Instant now) {
        this.name = requireNonNull(name);
        this.counting = new ApplicationCounting(config, now);
    }

    ApplicationName name() {
        return name;
    }

    // Moves the application's window and history to the hub's clock; the spans it observes from now on arrived then.
    void advance(Instant now) {
        counting.advance(now);
    }

    void received(Shedding shedding) {
        this.shedding = shedding.compareTo(this.shedding) > 0 ? shedding : this.shedding;
    }

    void invoked(InstanceName instance, MessageSpan span) {
        counting.invoked(instance, span);
        failedReceived += span.outcome() instanceof Outcome.Failed ? 1 : 0;
    }

    // Counted, but not retained, or not indexed and linked either.
    void shed(MessageSpan span, Shedding shedding) {
        if (shedding == Shedding.UNJOINED) {
            shedUnjoined++;
        } else {
            shedUnretained++;
        }
        var started = span.startedAt();
        shedThroughNanos = Math.max(shedThroughNanos, started.getEpochSecond() * 1_000_000_000L + started.getNano());
    }

    // Slower than the p95 of its stage over the window (P8).
    boolean slow(MessageSpan span) {
        return counting.slow(span);
    }

    void linked(Link link) {
        counting.linked(link);
    }

    void sent(SinkSend send) {
        counting.sent(send);
    }

    // A span or send that arrived again while the hub's span index held it.
    void duplicate() {
        duplicates++;
    }

    void failedBatch(int spans) {
        failedBatches += spans;
    }

    // The heap of the counts and the catalog.
    long countingBytes() {
        return counting.bytes();
    }

    void limit(boolean foldSooner, Duration history) {
        counting.limit(foldSooner, history);
    }

    // The start of the latest span the hub counted but did not retain, Long.MIN_VALUE while none.
    long shedThroughNanos() {
        return shedThroughNanos;
    }

    // The heaviest shedding of the batches received since the last call.
    Shedding takeShedding() {
        var heaviest = shedding;
        shedding = Shedding.NONE;
        return heaviest;
    }

    // The counts by reason that the application's own monitor observes.
    Map<HealthReason, Long> reasons() {
        var reasons = new EnumMap<HealthReason, Long>(HealthReason.class);
        reasons.put(HealthReason.SHED_UNRETAINED, shedUnretained);
        reasons.put(HealthReason.SHED_UNJOINED, shedUnjoined);
        reasons.put(HealthReason.DUPLICATE, duplicates);
        reasons.put(HealthReason.REVISED, counting.revised());
        reasons.put(HealthReason.BEYOND_HISTORY, counting.beyondHistory());
        reasons.put(HealthReason.OVERFLOW, counting.overflowed());
        reasons.put(HealthReason.FAILED_BATCH, failedBatches);
        return reasons;
    }

    // The retained are the specimens of the application by stage.
    ApplicationStatistics statistics(List<ReportingInstance> reporting, ToIntFunction<Stage> retained) {
        var stages = counting.stages(retained);
        return new ApplicationStatistics(name, failedReceived, counting.instances(reporting), stages.types(), stages.groups(), counting.links(),
            counting.crossings(), counting.routes(), counting.sinkRoutes(), counting.groupLinks(), stages.kinds().values().stream().reduce(Counts.NONE, Counts::plus),
            stages.handlings(), stages.eventDelay(), stages.kinds(), counting.enteredPerSecond(), counting.startedPerSecond(), counting.failedPerSecond(),
            counting.failures(), counting.other(), counting.provisionalFrom());
    }
}
