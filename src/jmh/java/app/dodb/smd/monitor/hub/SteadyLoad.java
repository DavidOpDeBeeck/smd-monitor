package app.dodb.smd.monitor.hub;

import app.dodb.smd.monitor.hub.RecordingInstance.MutableClock;
import app.dodb.smd.monitor.hub.budget.MemoryBudget;
import app.dodb.smd.monitor.hub.health.ApplicationHealth;
import app.dodb.smd.monitor.hub.health.HealthReason;
import app.dodb.smd.monitor.hub.intake.SpanBatch;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static java.util.Objects.requireNonNull;

// A hub at a design target in steady state: it received the generated load of its whole history and some seconds more, so its counts cover
// the history, its capacities are full, and it evicts as it receives more.
final class SteadyLoad {

    private static final long SEED = 20261016;
    private static final int EXTRA_SECONDS = 10;

    private final MutableClock clock = new MutableClock();
    private final LoadGenerator load;
    // Forgotten only to measure the heap the hub took.
    private SynchronousHub hub;
    private final MemoryBudget budget;
    private final ArrayDeque<SpanBatch> exported = new ArrayDeque<>();
    private long received;

    private SteadyLoad(LoadProfile profile) {
        this.load = new LoadGenerator(requireNonNull(profile), SEED, clock);
        this.budget = MemoryBudget.of(profile.memory());
        this.hub = new SynchronousHub(profile.hubConfig().build(), clock, budget);
    }

    // The design target with its memory budget.
    static SteadyLoad filled() {
        return filled(LoadProfile.STANDALONE.memory());
    }

    static SteadyLoad filled(long memory) {
        var design = LoadProfile.STANDALONE;
        var profile = new LoadProfile(design.applications(), design.instances(), design.types(), design.groups(), design.spansPerSecond(), design.window(),
            design.history(), memory);
        var steady = new SteadyLoad(profile);
        var seconds = profile.history().toSeconds() + EXTRA_SECONDS;
        var started = System.nanoTime();
        for (var second = 0; second < seconds; second++) {
            steady.clock.advance(Duration.ofSeconds(1));
            for (var report : steady.load.export()) {
                steady.receive(report);
            }
        }
        steady.describe(Duration.ofNanos(System.nanoTime() - started));
        return steady;
    }

    SynchronousHub hub() {
        return hub;
    }

    MutableClock clock() {
        return clock;
    }

    MemoryBudget budget() {
        return budget;
    }

    void forgetHub() {
        hub = null;
    }

    void receive(SpanBatch report) {
        hub.receive(report);
        received += report.size();
    }

    // The reports every instance exported in the next second of the load.
    List<SpanBatch> nextSecond() {
        clock.advance(Duration.ofSeconds(1));
        return load.export();
    }

    // The next reports holding the given number of spans and sends together, as an OTLP request of several resources would; the last report is
    // split when it holds more. The clock advances a second whenever the reports exported so far are spent.
    List<SpanBatch> next(int spans) {
        var batch = new ArrayList<SpanBatch>();
        var remaining = spans;
        while (remaining > 0) {
            while (exported.isEmpty()) {
                clock.advance(Duration.ofSeconds(1));
                exported.addAll(load.export());
            }
            var report = exported.poll();
            if (report.size() <= remaining) {
                batch.add(report);
                remaining -= report.size();
                continue;
            }
            var spanCount = Math.min(remaining, report.spans().size());
            var sendCount = remaining - spanCount;
            batch.add(new SpanBatch(report.application(), report.instance(), report.session(), report.spans().subList(0, spanCount),
                report.sends().subList(0, sendCount)));
            exported.addFirst(new SpanBatch(report.application(), report.instance(), report.session(), report.spans().subList(spanCount, report.spans().size()),
                report.sends().subList(sendCount, report.sends().size())));
            remaining = 0;
        }
        return batch;
    }

    // The heap used after a collection includes the load generator's state, which retains every invocation an event store holds back.
    private void describe(Duration filling) {
        var epoch = hub.epoch();
        var health = epoch.health();
        var memory = health.memory();
        var retained = health.applications().stream().mapToLong(ApplicationHealth::retained).sum();
        var evicted = health.applications().stream().mapToLong(application -> application.count(HealthReason.EVICTED)).sum();
        var types = epoch.statistics().applications().stream().mapToLong(application -> application.types().size()).sum();
        var horizon = health.applications().stream().mapToLong(application -> application.effectiveHorizon().toSeconds()).min().orElse(0);
        System.gc();
        var runtime = Runtime.getRuntime();
        System.out.printf("%nSteady load: received %,d spans in %d s; retains %,d invocations of %,d types and dropped %,d; accounts %,d of %,d MB "
                + "(queues %,d, counting %,d, join %,d, specimens %,d of %,d); history %d s%s, shortest horizon %d s; heap used %,d MB%n", received,
            filling.toSeconds(), retained, types, evicted, megabytes(memory.bytes()), megabytes(memory.budget()), megabytes(memory.queues().bytes()),
            megabytes(memory.counting().bytes()), megabytes(memory.join().bytes()), megabytes(memory.specimens().bytes()), megabytes(memory.specimens().limit()),
            health.effectiveHistory().toSeconds(), health.foldsSooner() ? " folding sooner" : "", horizon, megabytes(runtime.totalMemory() - runtime.freeMemory()));
    }

    private static long megabytes(long bytes) {
        return bytes / (1024 * 1024);
    }
}
