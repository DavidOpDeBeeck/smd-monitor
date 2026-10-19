package app.dodb.smd.monitor.hub;

import app.dodb.smd.monitor.hub.budget.MemoryBudget;
import app.dodb.smd.monitor.hub.health.HealthView;
import app.dodb.smd.monitor.hub.intake.Refusal;
import app.dodb.smd.monitor.hub.intake.SpanBatch;
import app.dodb.smd.monitor.hub.span.ApplicationName;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

// A monitor hub whose writer runs on the calling thread instead of its own: a batch is admitted, queued, and received at once, and every read
// builds a new epoch at the clock's instant, as the writer does at its tick. Tests and benchmarks read the hub deterministically through it.
public final class SynchronousHub {

    private final Clock clock;
    private final MonitorHub hub;

    public SynchronousHub(HubConfig config) {
        this(config, Clock.systemUTC());
    }

    public SynchronousHub(HubConfig config, Clock clock) {
        this(config, clock, MemoryBudget.of(config.memory()));
    }

    public SynchronousHub(HubConfig config, Clock clock, MemoryBudget budget) {
        this.clock = requireNonNull(clock);
        this.hub = new MonitorHub(config, clock, budget, Long.MAX_VALUE);
    }

    // The hub itself, never started: what is submitted to it directly is received when the next epoch is built.
    public MonitorHub hub() {
        return hub;
    }

    public Submission submit(SpanBatch batch) {
        var submission = hub.submit(batch);
        receiveQueued();
        return submission;
    }

    public Optional<Refusal> receive(SpanBatch batch) {
        return switch (submit(batch)) {
            case Submission.Accepted(var refusal) -> refusal;
            case Submission.Busy busy -> throw new AssertionError("A synchronous hub's queues never fill; got " + busy);
        };
    }

    public Epoch epoch() {
        receiveQueued();
        return hub.writer().tick(clock.instant());
    }

    public HubStatistics statistics() {
        return epoch().statistics();
    }

    public HealthView health() {
        return epoch().health();
    }

    public UUID session() {
        return epoch().session();
    }

    public TapePage tape(TapeQuery query) {
        return epoch().tape(query);
    }

    public DurationHistogram durations(InvocationFilter filter, Duration threshold, int buckets) {
        return epoch().durations(filter, threshold, buckets);
    }

    public TracePage traces(TraceQuery query) {
        return epoch().traces(query);
    }

    public Optional<TraceDetail> trace(InvocationId member) {
        return epoch().trace(member);
    }

    public Failures failures(List<FailureCause.Step> causes) {
        return epoch().failures(causes);
    }

    public TimelineWindow timeline(TimelineQuery query) {
        return epoch().timeline(query);
    }

    public Optional<MessageDetail> message(InvocationId id) {
        return epoch().message(id);
    }

    public RetainedType type(ApplicationType type) {
        return epoch().type(type);
    }

    public List<Invocation> withMessageIdPrefix(Optional<ApplicationName> scope, String prefix, int limit) {
        return epoch().withMessageIdPrefix(scope, prefix, limit);
    }

    Retained retained() {
        var epoch = epoch();
        return new Retained(epoch.statistics().sequence(), epoch.invocations(), epoch.parents());
    }

    private void receiveQueued() {
        try {
            hub.writer().receiveQueued();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while receiving the queued batches", interrupted);
        }
    }
}
