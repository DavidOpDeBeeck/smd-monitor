package app.dodb.smd.monitor.hub;

import java.util.List;

import static java.util.Objects.requireNonNull;

// How many invocations took how long, in buckets of equal width on a log scale from 10 microseconds to 60 seconds, the top bound of the latency
// histograms; the outer buckets also hold everything beyond. The slower took at least the threshold.
public record DurationHistogram(long sequence, int invocations, int slower, List<Integer> buckets) {

    public static final int MAX_BUCKETS = 200;
    private static final double LOW = -2;
    private static final double HIGH = Math.log10(60_000);

    public DurationHistogram {
        requireNonNull(buckets);
    }

    static int bucketOf(double millis, int buckets) {
        var position = millis <= 0 ? 0 : Math.clamp((Math.log10(millis) - LOW) / (HIGH - LOW), 0.0, 1.0);
        return Math.min(buckets - 1, (int) Math.floor(position * buckets));
    }
}
