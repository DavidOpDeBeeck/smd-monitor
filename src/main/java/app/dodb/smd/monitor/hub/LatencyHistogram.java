package app.dodb.smd.monitor.hub;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static java.util.Objects.requireNonNull;

public record LatencyHistogram(List<Long> counts, Duration total, Duration max) {

    // The upper bounds of the 19 buckets but the last, which holds everything slower.
    public static final List<Duration> BOUNDS = List.of(
        Duration.ofNanos(100_000), Duration.ofNanos(250_000), Duration.ofNanos(500_000),
        Duration.ofMillis(1), Duration.ofNanos(2_500_000), Duration.ofMillis(5),
        Duration.ofMillis(10), Duration.ofMillis(25), Duration.ofMillis(50),
        Duration.ofMillis(100), Duration.ofMillis(250), Duration.ofMillis(500),
        Duration.ofSeconds(1), Duration.ofMillis(2_500), Duration.ofSeconds(5), Duration.ofSeconds(10),
        Duration.ofSeconds(25), Duration.ofSeconds(60));
    static final LatencyHistogram EMPTY = new LatencyHistogram(Collections.nCopies(BOUNDS.size() + 1, 0L), Duration.ZERO, Duration.ZERO);

    public LatencyHistogram {
        requireNonNull(counts);
        requireNonNull(total);
        requireNonNull(max);
        if (counts.size() != BOUNDS.size() + 1 || !nonnegative(counts) || total.isNegative() || max.isNegative()) {
            throw new IllegalArgumentException("LatencyHistogram requires " + (BOUNDS.size() + 1) + " nonnegative bucket counts and nonnegative durations; got "
                + counts + ", total " + total + ", max " + max);
        }
    }

    static LatencyHistogram of(Duration duration) {
        var counts = new ArrayList<>(EMPTY.counts);
        counts.set(bucketOf(duration), 1L);
        return new LatencyHistogram(counts, duration, duration);
    }

    public long count() {
        if (counts instanceof LongList unboxed) {
            return unboxed.sum();
        }
        if (counts instanceof IntList unboxed) {
            return unboxed.sum();
        }
        var count = 0L;
        for (var bucket : counts) {
            count += bucket;
        }
        return count;
    }

    public Optional<Duration> average() {
        var count = count();
        return count == 0 ? Optional.empty() : Optional.of(total.dividedBy(count));
    }

    // Interpolated within its bucket, which ends at the slowest invocation when that is faster than the bucket's bound.
    public Optional<Duration> percentile(double fraction) {
        var target = count() * fraction;
        var seen = 0L;
        for (var bucket = 0; bucket < counts.size(); bucket++) {
            var count = counts.get(bucket);
            if (count > 0 && seen + count >= target) {
                var lower = bucket == 0 ? Duration.ZERO : BOUNDS.get(bucket - 1);
                var upper = bucket < BOUNDS.size() && BOUNDS.get(bucket).compareTo(max) < 0 ? BOUNDS.get(bucket) : max;
                var within = (target - seen) / count;
                return Optional.of(lower.plusNanos(Math.round((upper.toNanos() - lower.toNanos()) * within)));
            }
            seen += count;
        }
        return Optional.empty();
    }

    // Where the fastest bucket that holds an invocation starts. The first bucket starts at zero, which a log scale cannot show, so
    // it starts at half its bound.
    public Optional<Duration> floor() {
        var bucket = IntStream.range(0, counts.size()).filter(index -> counts.get(index) > 0).findFirst();
        if (bucket.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(bucket.getAsInt() == 0 ? BOUNDS.getFirst().dividedBy(2) : BOUNDS.get(bucket.getAsInt() - 1));
    }

    public LatencyHistogram plus(LatencyHistogram other) {
        var summed = new long[counts.size()];
        for (var bucket = 0; bucket < summed.length; bucket++) {
            summed[bucket] = bucket(bucket) + other.bucket(bucket);
        }
        return new LatencyHistogram(new LongList(summed), total.plus(other.total), max.compareTo(other.max) >= 0 ? max : other.max);
    }

    private long bucket(int bucket) {
        if (counts instanceof LongList unboxed) {
            return unboxed.at(bucket);
        }
        return counts instanceof IntList unboxed ? unboxed.at(bucket) : counts.get(bucket);
    }

    private static boolean nonnegative(List<Long> counts) {
        if (counts instanceof LongList unboxed) {
            return unboxed.nonnegative();
        }
        if (counts instanceof IntList unboxed) {
            return unboxed.nonnegative();
        }
        for (var count : counts) {
            if (count < 0) {
                return false;
            }
        }
        return true;
    }

    static int bucketOf(Duration duration) {
        var bucket = 0;
        while (bucket < BOUNDS.size() && duration.compareTo(BOUNDS.get(bucket)) > 0) {
            bucket++;
        }
        return bucket;
    }
}
