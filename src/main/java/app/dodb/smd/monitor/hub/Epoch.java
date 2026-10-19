package app.dodb.smd.monitor.hub;

import app.dodb.smd.monitor.hub.health.HealthView;
import app.dodb.smd.monitor.hub.span.ApplicationName;
import app.dodb.smd.monitor.hub.specimen.SpecimenView;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static java.util.Comparator.comparingLong;
import static java.util.Objects.requireNonNull;

// What the hub counted and retained at one tick of its writer, and its health. It never changes once published, so the frame and the queries read it without
// holding the hub; its specimens and traces share every chunk that did not change since the epoch before.
public final class Epoch {

    static final int MAX_FOUND = 100;
    static final int MAX_CAUSES = 100;

    private final long sequence;
    private final Instant at;
    private final HubStatistics statistics;
    private final HealthView health;
    private final SpecimenView specimens;
    private final Queries queries;

    Epoch(long sequence, Instant at, HubStatistics statistics, HealthView health, SpecimenView specimens) {
        this.sequence = sequence;
        this.at = requireNonNull(at);
        this.statistics = requireNonNull(statistics);
        this.health = requireNonNull(health);
        this.specimens = requireNonNull(specimens);
        this.queries = new Queries(statistics.sequence(), specimens);
    }

    // Counts the epochs of one session of the hub, from 1.
    public long sequence() {
        return sequence;
    }

    public Instant at() {
        return at;
    }

    public UUID session() {
        return statistics.session();
    }

    public HubStatistics statistics() {
        return statistics;
    }

    public HealthView health() {
        return health;
    }

    public TapePage tape(TapeQuery query) {
        return queries.tape(query);
    }

    public DurationHistogram durations(InvocationFilter filter, Duration threshold, int buckets) {
        return queries.durations(filter, threshold, buckets);
    }

    public TracePage traces(TraceQuery query) {
        return queries.traces(query);
    }

    public Optional<TraceDetail> trace(InvocationId member) {
        return queries.trace(member);
    }

    public Failures failures(List<FailureCause.Step> causes) {
        if (causes.size() > MAX_CAUSES) {
            throw new IllegalArgumentException("A failures query asks for at most " + MAX_CAUSES + " causes; got " + causes.size());
        }
        return queries.failures(causes);
    }

    public TimelineWindow timeline(TimelineQuery query) {
        return queries.timeline(query, at);
    }

    public Optional<MessageDetail> message(InvocationId id) {
        return queries.message(id);
    }

    public RetainedType type(ApplicationType type) {
        return queries.type(type);
    }

    // The counts of the type over the history, empty when the application did not report it.
    public Optional<Counts> history(ApplicationType type) {
        return statistics.applications().stream()
            .filter(application -> application.application().equals(type.application()))
            .flatMap(application -> application.types().stream())
            .filter(statistics -> statistics.type().equals(type.type()))
            .map(TypeStatistics::history)
            .findFirst();
    }

    public List<Invocation> withMessageIdPrefix(Optional<ApplicationName> scope, String prefix, int limit) {
        if (limit < 1 || limit > MAX_FOUND) {
            throw new IllegalArgumentException("A search by message ID prefix returns between 1 and " + MAX_FOUND + " invocations; got a limit of " + limit);
        }
        return queries.withMessageIdPrefix(scope, prefix, limit);
    }

    // Every retained invocation, oldest first.
    List<Invocation> invocations() {
        var invocations = new ArrayList<Invocation>(specimens.size());
        specimens.forEach(specimen -> invocations.add(specimen.invocation()));
        return invocations;
    }

    // The summaries of the parents found for retained invocations, in the order they were found.
    List<ParentSummary> parents() {
        var parents = new ArrayList<ParentSummary>();
        specimens.forEach(specimen -> specimen.parent().ifPresent(parents::add));
        parents.sort(comparingLong(ParentSummary::sequence));
        return parents;
    }
}
