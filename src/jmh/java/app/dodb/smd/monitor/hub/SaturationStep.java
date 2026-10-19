package app.dodb.smd.monitor.hub;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

// One rate of a saturation run, offered from its first to its last second, summarized over the seconds after it settled: what was offered and
// processed, what the hub refused and shed, how full its queues ran, the writer's share of a core and of the wall clock, the process's cores, how
// late the writer ticked and published, the frame the publisher wrote, and what the hub retained. The writer is busy whenever it does not wait
// for a batch: running, waiting for a core, or paused by a collection.
record SaturationStep(int rate, long firstSecond, long lastSecond, double offeredPerSecond, double processedPerSecond, double busyShare, long refused,
                      double unretainedShare, double unjoinedShare, long maxQueued, long endQueued, long queueCapacity, double writerCpu, double writerBusyMean,
                      double writerBusyMax, double processCores,
                      double tickLateP99Millis, double epochLagP99Millis, double frameMillisP50, double frameMillisMax, double deltaKilobytesP50,
                      long framesSkipped, double gcMillisPerSecond, long heapMaxMegabytes, double generatorLateMillis, double submitLateMillis,
                      long retained, long horizonSeconds, long historySeconds, long accountedMegabytes, boolean generatorBound,
                      boolean stalled) {

    static final String HEADER = "| Rate | Offered/s | Processed/s | Busy | Shed unretained, unjoined | Queue max, end | Writer CPU | Writer busy mean, max | Process cores | Tick late p99 ms "
        + "| Epoch lag p99 ms | Frame p50, max ms | Delta KB p50 | Frames skipped | GC ms/s | Heap MB | Accounted MB | Retained | Horizon s | History s "
        + "| Late ms: generator, submit | Verdict |";
    static final String SEPARATOR = "|---".repeat(22) + "|";

    private static final double WRITER_BUSY_LIMIT = 0.9;
    private static final double PROCESSED_LIMIT = 0.95;

    // A step in which the writer published no epoch, so the run sampled nothing.
    static SaturationStep stalled(int rate, long firstSecond, long lastSecond, long queueCapacity) {
        return new SaturationStep(rate, firstSecond, lastSecond, 0, 0, 0, 0, 0, 0, 0, 0, queueCapacity, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false, true);
    }

    Verdict verdict() {
        if (stalled) {
            return Verdict.STALLED;
        }
        if (generatorBound) {
            return Verdict.GENERATOR_BOUND;
        }
        if (busyShare > 0) {
            return Verdict.REFUSES;
        }
        if (writerBusyMean > WRITER_BUSY_LIMIT || endQueued > queueCapacity / 4 || processedPerSecond < PROCESSED_LIMIT * offeredPerSecond) {
            return Verdict.BEHIND;
        }
        return unretainedShare + unjoinedShare > 0 ? Verdict.SHEDS : Verdict.KEEPS_UP;
    }

    String row() {
        if (stalled) {
            return "| %,d |".formatted(rate) + " - |".repeat(20) + " " + verdict() + " |";
        }
        return "| %,d | %,.0f | %,.0f | %s | %s, %s | %,d, %,d | %.2f | %.2f, %.2f | %.1f | %.0f | %.0f | %.0f, %.0f | %,.0f | %d | %.0f | %,d | %,d | %,d | %d | %d | %.0f, %.0f | %s |"
            .formatted(rate, offeredPerSecond, processedPerSecond, percent(busyShare), percent(unretainedShare), percent(unjoinedShare), maxQueued, endQueued,
                writerCpu, writerBusyMean, writerBusyMax, processCores, tickLateP99Millis, epochLagP99Millis, frameMillisP50, frameMillisMax, deltaKilobytesP50, framesSkipped,
                gcMillisPerSecond, heapMaxMegabytes, accountedMegabytes, retained, horizonSeconds, historySeconds, generatorLateMillis, submitLateMillis,
                verdict());
    }

    // The step on one console line: its verdict and the measures it turns on; the row has the rest.
    String line() {
        var offered = "%,d spans/s for %d s: %s".formatted(rate, lastSecond - firstSecond + 1, verdict());
        if (stalled) {
            return offered + ", no epoch published";
        }
        return "%s; processed %,.0f/s, writer busy %.0f%% (max %.0f%%) at %.2f cores, queue max %,d of %,d, answered busy %s, shed %s, heap %,d MB"
            .formatted(offered, processedPerSecond, 100 * writerBusyMean, 100 * writerBusyMax, writerCpu, maxQueued, queueCapacity, percent(busyShare),
                percent(unretainedShare + unjoinedShare), heapMaxMegabytes);
    }

    // The highest rate the hub kept up with, with and without shedding, and the first it failed at.
    static String verdictOf(List<SaturationStep> steps) {
        return "Keeps up without shedding up to " + spansPerSecond(keptUpTo(steps)) + "; without refusing or falling behind up to "
            + spansPerSecond(heldTo(steps)) + "; "
            + firstFailure(steps).map(step -> "first fails at %,d spans/s: %s.".formatted(step.rate(), step.verdict())).orElse("never failed within the rates offered.");
    }

    static OptionalInt keptUpTo(List<SaturationStep> steps) {
        return steps.stream().filter(step -> step.verdict() == Verdict.KEEPS_UP).mapToInt(SaturationStep::rate).max();
    }

    // The highest rate it kept up with, shedding or not.
    static OptionalInt heldTo(List<SaturationStep> steps) {
        return steps.stream().filter(step -> !step.verdict().failed()).mapToInt(SaturationStep::rate).max();
    }

    static Optional<SaturationStep> firstFailure(List<SaturationStep> steps) {
        return steps.stream().filter(step -> step.verdict().failed()).findFirst();
    }

    private static String spansPerSecond(OptionalInt rate) {
        return rate.isPresent() ? "%,d spans/s".formatted(rate.getAsInt()) : "no rate";
    }

    private static String percent(double share) {
        return share == 0 ? "0" : "%.1f%%".formatted(100 * share);
    }

    enum Verdict {
        KEEPS_UP,
        // Counted everything, but retained or linked less, as a queue was at least half full when the writer took a batch.
        SHEDS,
        // The writer was busy more than 90% of the time, ended the step with a quarter of the queues full, or processed less than it was offered.
        BEHIND,
        // A queue was full and the hub answered busy.
        REFUSES,
        // The load generator could not produce the rate in time, so the step did not offer it.
        GENERATOR_BOUND,
        // The writer published no epoch during the step.
        STALLED;

        boolean failed() {
            return this != KEEPS_UP && this != SHEDS;
        }
    }
}
