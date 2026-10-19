package app.dodb.smd.monitor.hub;

import app.dodb.smd.monitor.hub.budget.FairShare;
import app.dodb.smd.monitor.hub.budget.Footprint;
import app.dodb.smd.monitor.hub.budget.MemoryBudget;
import app.dodb.smd.monitor.hub.health.ApplicationHealth;
import app.dodb.smd.monitor.hub.health.HealthReason;
import app.dodb.smd.monitor.hub.health.HealthView;
import app.dodb.smd.monitor.hub.health.MemoryUse;
import app.dodb.smd.monitor.hub.health.ProductUse;
import app.dodb.smd.monitor.hub.intake.QueueHealth;
import app.dodb.smd.monitor.hub.intake.RefusedSpans;
import app.dodb.smd.monitor.hub.intake.ReportingInstance;
import app.dodb.smd.monitor.hub.intake.Shedding;
import app.dodb.smd.monitor.hub.intake.SpanBatch;
import app.dodb.smd.monitor.hub.join.Fact;
import app.dodb.smd.monitor.hub.join.Link;
import app.dodb.smd.monitor.hub.join.SpanIndex;
import app.dodb.smd.monitor.hub.span.ApplicationName;
import app.dodb.smd.monitor.hub.span.Outcome;
import app.dodb.smd.monitor.hub.specimen.Specimen;
import app.dodb.smd.monitor.hub.specimen.Specimens;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import static java.util.Comparator.comparing;
import static java.util.Objects.requireNonNull;

// What the hub counts, links, and retains of every application, within its memory budget. Only the writer thread touches it, so it takes no
// locks; readers see it through the immutable epochs it builds at each tick.
final class Hub {

    private static final Duration BUCKET = Duration.ofSeconds(10);
    private static final double RELAX_BELOW = 0.8;

    private final UUID session = UUID.randomUUID();
    private final HubConfig config;
    private final MemoryBudget budget;
    private final Map<ApplicationName, ApplicationMonitor> applications = new TreeMap<>(comparing(ApplicationName::value));
    private final SpanIndex index;
    private final Specimens specimens;
    // Numbers each retained invocation and each parent found, in every application, so the sequence number tells what the hub retains.
    private long sequence;
    private long epochs;
    // How counting gives way under memory pressure, the same for every application (A8).
    private boolean foldSooner;
    private Duration history;
    // What the specimens may take, as of the last tick.
    private long specimenAllowance;

    Hub(HubConfig config, MemoryBudget budget) {
        this.config = requireNonNull(config);
        this.budget = requireNonNull(budget);
        this.index = new SpanIndex(config.linkHorizon());
        this.specimens = new Specimens(index::release);
        this.history = config.history();
        this.specimenAllowance = budget.specimens(0, 0);
    }

    // Admission already refused what is beyond the hub's limits. Every span is counted (C5). Above half a full queue, only the failed and the slow
    // spans become specimens; above 80%, spans are deduplicated but neither indexed nor joined. The specimens then fit their allowance.
    void receive(SpanBatch batch, Shedding shedding, Instant now) {
        var application = monitor(batch.application(), now);
        application.advance(now);
        application.received(shedding);
        for (var span : batch.spans()) {
            // A span the index holds arrived before and is not counted again.
            if (index.knows(span.span())) {
                application.duplicate();
                continue;
            }
            var id = new InvocationId(++sequence);
            application.invoked(batch.instance(), span);
            if (shedding == Shedding.UNJOINED) {
                application.shed(span, shedding);
                continue;
            }
            var retained = shedding == Shedding.NONE || span.outcome() instanceof Outcome.Failed || application.slow(span);
            var fact = new Fact(id, application.name(), span.stage(), span.messageId(), span.startedAt(), span.outcome().duration());
            var links = index.receive(span.span(), span.continues(), fact, now, retained);
            if (retained) {
                specimens.retain(id, application.name(), batch.instance(), span);
            } else {
                application.shed(span, shedding);
            }
            links.forEach(this::linked);
        }
        for (var send : batch.sends()) {
            if (index.knows(send.span())) {
                application.duplicate();
                continue;
            }
            if (shedding != Shedding.UNJOINED) {
                index.receiveSend(send.span(), application.name(), now);
            }
            application.sent(send);
        }
        specimens.fit(specimenAllowance);
    }

    // The batch failed part way; the spans it held are counted as dropped.
    void failed(SpanBatch batch, Instant now) {
        monitor(batch.application(), now).failedBatch(batch.size());
    }

    // Drops everything the hub holds of an application admission retired.
    void retire(ApplicationName name) {
        var application = applications.remove(name);
        if (application == null) {
            return;
        }
        specimens.retire(name);
        index.forget(name);
    }

    // Lists every application admission let report, even one whose spans the hub has not received; moves every application's window and
    // history to now, also of the applications that sent nothing since; fits the memory budget; and describes the next epoch.
    Epoch tick(Instant now, Map<ApplicationName, List<ReportingInstance>> reporting, RefusedSpans refused, QueueHealth queues) {
        reporting.keySet().forEach(name -> monitor(name, now));
        index.expire(now);
        applications.values().forEach(application -> application.advance(now));
        var end = Instant.ofEpochSecond(now.getEpochSecond()).minus(config.settle());
        var windowStart = end.minus(config.window());
        specimens.expire(windowStart.getEpochSecond() * 1_000_000_000L);
        fit(now);
        var view = specimens.tick();
        var retentions = new HashMap<ApplicationName, Specimens.Retention>();
        applications.keySet().forEach(name -> retentions.put(name, specimens.retention(name)));
        var statistics = new HubStatistics(session, sequence, end, config.window(), history, config.settle(), config.retireAfter(),
            applications.values().stream()
                .map(application -> application.statistics(reporting.getOrDefault(application.name(), List.of()),
                    stage -> specimens.retained(application.name(), stage)))
                .toList());
        var health = health(retentions, refused, queues);
        return new Epoch(++epochs, now, statistics, health, view);
    }

    // Fits the products within the budget in the order of TR10. Counting and the join first keep within their ceilings (M1); the specimens then
    // take what is left, giving way first. Once the specimens reached their floor, the join gives way further, and counting last. Counting and
    // the reservoirs' K recover a step at a tick once they take less than 80% of what they may.
    private void fit(Instant now) {
        var counted = countingBytes();
        while (counted > budget.counting() && limitCounting()) {
            counted = countingBytes();
        }
        // A specimen the hub drops leaves its span to the join state within the horizon, so the join is fitted again until the specimens fit what
        // it leaves them; each round frees more than it hands over.
        boolean fits;
        do {
            index.fit(budget.join(), now);
            specimenAllowance = budget.specimens(counted, index.bytes());
            fits = specimens.fit(specimenAllowance);
        } while (fits && beyondTheBudget(counted) && specimens.bytes() > 0);
        if (!fits || beyondTheBudget(counted)) {
            index.fit(Math.max(0, budget.total() - budget.queues() - counted - specimens.bytes()), now);
            while (beyondTheBudget(counted) && limitCounting()) {
                counted = countingBytes();
            }
            specimenAllowance = budget.specimens(counted, index.bytes());
            return;
        }
        if (counted < budget.counting() * RELAX_BELOW && budget.queues() + counted + index.bytes() + specimens.bytes() < budget.total() * RELAX_BELOW) {
            relaxCounting();
        }
        specimens.relax(specimenAllowance);
    }

    private boolean beyondTheBudget(long counted) {
        return budget.queues() + counted + index.bytes() + specimens.bytes() > budget.total();
    }

    // Folds seconds sooner, then shortens the history a bucket at a time down to the window; false when counting cannot give way further.
    private boolean limitCounting() {
        if (!foldSooner) {
            foldSooner = true;
        } else if (history.minus(BUCKET).compareTo(config.window()) >= 0) {
            history = history.minus(BUCKET);
        } else {
            return false;
        }
        applications.values().forEach(application -> application.limit(foldSooner, history));
        return true;
    }

    private void relaxCounting() {
        if (history.compareTo(config.history()) < 0) {
            history = history.plus(BUCKET).compareTo(config.history()) > 0 ? config.history() : history.plus(BUCKET);
        } else if (foldSooner) {
            foldSooner = false;
        } else {
            return;
        }
        applications.values().forEach(application -> application.limit(foldSooner, history));
    }

    // The counts and catalogs of every application, and the names their specimens share.
    private long countingBytes() {
        return applications.values().stream().mapToLong(application -> application.countingBytes() + specimens.namesBytes(application.name())).sum();
    }

    private HealthView health(Map<ApplicationName, Specimens.Retention> retentions, RefusedSpans refused, QueueHealth queues) {
        var counted = countingBytes();
        var memory = new MemoryUse(budget.total(), new ProductUse(queues.queued() * Footprint.QUEUED_SPAN, budget.queues()),
            new ProductUse(counted, budget.counting()), new ProductUse(index.bytes(), budget.join()), new ProductUse(specimens.bytes(), specimenAllowance));
        var oldest = specimens.oldest();
        var joinShare = FairShare.of(budget.join(), applications.size());
        var specimenShare = FairShare.of(specimenAllowance, applications.size());
        var applicationHealth = applications.values().stream()
            .map(application -> {
                var name = application.name();
                var retention = retentions.get(name);
                var reasons = new EnumMap<>(application.reasons());
                reasons.put(HealthReason.REFUSED_BY_INSTANCE_LIMIT, refused.beyondInstances().getOrDefault(name, 0L));
                reasons.put(HealthReason.REFUSED_AHEAD_OF_CLOCK, refused.aheadOfClock().getOrDefault(name, 0L));
                reasons.put(HealthReason.QUEUE_FULL, queues.full().getOrDefault(name, 0L));
                reasons.put(HealthReason.PARENT_NEVER_RECEIVED, index.unlinked(name).parentNeverReceived());
                reasons.put(HealthReason.PARENT_AFTER_HORIZON, index.unlinked(name).parentAfterHorizon());
                reasons.put(HealthReason.FRAGMENT, retention.fragments());
                reasons.put(HealthReason.EVICTED, retention.evicted());
                var incompleteThrough = Math.max(retention.droppedThroughNanos(), application.shedThroughNanos());
                var completeSince = incompleteThrough == Long.MIN_VALUE ? Optional.<Instant>empty()
                    : Optional.of(Instant.ofEpochSecond(Math.floorDiv(incompleteThrough, 1_000_000_000L), Math.floorMod(incompleteThrough, 1_000_000_000L)));
                return new ApplicationHealth(name, Map.copyOf(reasons), application.takeShedding(), application.countingBytes() + retention.namesBytes(),
                    new ProductUse(index.bytes(name), joinShare), new ProductUse(retention.bytes(), specimenShare), retention.retained(), completeSince,
                    Optional.ofNullable(oldest.get(name)).map(Specimen::startedAt), index.effectiveHorizon(name), retention.slowestK(), retention.failuresK());
            })
            .toList();
        return new HealthView(memory, config.history(), history, foldSooner, config.linkHorizon(), refused.beyondApplications(), applicationHealth);
    }

    // A new application counts like the others under memory pressure.
    private ApplicationMonitor monitor(ApplicationName name, Instant now) {
        return applications.computeIfAbsent(name, ignored -> {
            var application = new ApplicationMonitor(name, config, now);
            if (foldSooner || history.compareTo(config.history()) < 0) {
                application.limit(foldSooner, history);
            }
            return application;
        });
    }

    // Counted in the application of the child, which keeps a summary of its parent while it retains the child.
    private void linked(Link link) {
        var child = link.child();
        applications.get(child.application()).linked(link);
        if (specimens.retains(child.id())) {
            var parent = link.parent();
            specimens.parentFound(new ParentSummary(++sequence, child.id(), parent.id(), parent.application(), parent.messageId(), parent.stage(),
                link.delivered()));
        }
    }
}
