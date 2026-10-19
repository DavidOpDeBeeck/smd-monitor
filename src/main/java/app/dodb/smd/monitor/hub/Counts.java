package app.dodb.smd.monitor.hub;

import static java.util.Objects.requireNonNull;

// An invocation counts in the second it started, with its latency and failure.
public record Counts(long started, long entered, long failed, LatencyHistogram latency) {

    public static final Counts NONE = new Counts(0, 0, 0, LatencyHistogram.EMPTY);

    public Counts {
        requireNonNull(latency);
        if (entered < 0 || entered > started || failed < 0 || failed > latency.count()) {
            throw new IllegalArgumentException("Counts require entry points within the started invocations, and failures within the completed invocations; "
                + "got started " + started + ", entered " + entered + ", failed " + failed + ", completed " + latency.count());
        }
    }

    public long completed() {
        return latency.count();
    }

    public Counts plus(Counts other) {
        return new Counts(started + other.started, entered + other.entered, failed + other.failed, latency.plus(other.latency));
    }
}
