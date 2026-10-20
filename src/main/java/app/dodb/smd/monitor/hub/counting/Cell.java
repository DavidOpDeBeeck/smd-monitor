package app.dodb.smd.monitor.hub.counting;

import app.dodb.smd.monitor.hub.Counts;
import app.dodb.smd.monitor.hub.IntList;
import app.dodb.smd.monitor.hub.LatencyHistogram;

import java.time.Duration;

// The counts of one timed key in one second, one 10-second bucket, or a running total: how many started, entered, and failed, and a latency
// histogram with its total and maximum. Most cells of a second see one or two latency buckets (A3), so the histogram keeps up to two of them
// packed inline, each a bucket and its count, and becomes an array of every bucket once a third arrives. Counts fit an int: a key counts at
// most 10,000 spans a second over a 15-minute history. Owned by the writer; nothing is allocated once the array is. A running total keeps the
// counts it last read out until it changes, so the epochs share them meanwhile instead of each holding a copy: most keys' totals do not
// change in a second, and what an epoch holds survives the collections during its second.
final class Cell {

    private static final long[] BOUNDS_NANOS = LatencyHistogram.BOUNDS.stream().mapToLong(Duration::toNanos).toArray();
    private static final int BUCKETS = BOUNDS_NANOS.length + 1;
    // An inline slot holds its bucket plus one in the top 5 bits and its count below; zero is empty.
    private static final int COUNT_BITS = 27;
    private static final int MAX_INLINE_COUNT = (1 << COUNT_BITS) - 1;

    int started;
    int entered;
    int failed;
    private int first;
    private int second;
    private int[] buckets;
    private long totalNanos;
    private long maxNanos;
    // Immutable; null once the cell changed since it was read.
    private Counts counts;

    // True when the histogram became an array.
    boolean countInvocation(boolean entry, boolean failure, long nanos) {
        started++;
        entered += entry ? 1 : 0;
        failed += failure ? 1 : 0;
        return countDuration(nanos);
    }

    // True when the histogram became an array.
    boolean countDuration(long nanos) {
        counts = null;
        var promoted = addToBucket(bucketOf(nanos), 1);
        totalNanos += nanos;
        maxNanos = Math.max(maxNanos, nanos);
        return promoted;
    }

    // True when the histogram became an array.
    boolean add(Cell other) {
        counts = null;
        started += other.started;
        entered += other.entered;
        failed += other.failed;
        var promoted = addBuckets(other, 1);
        totalNanos += other.totalNanos;
        maxNanos = Math.max(maxNanos, other.maxNanos);
        return promoted;
    }

    // True when the subtracted cell held the maximum, which the caller then finds again among the cells that remain.
    boolean subtract(Cell other) {
        counts = null;
        started -= other.started;
        entered -= other.entered;
        failed -= other.failed;
        addBuckets(other, -1);
        totalNanos -= other.totalNanos;
        var heldMaximum = other.maxNanos > 0 && other.maxNanos >= maxNanos;
        if (completed() == 0) {
            maxNanos = 0;
            return false;
        }
        return heldMaximum;
    }

    void resetMaximum() {
        counts = null;
        maxNanos = 0;
    }

    void raiseMaximum(Cell other) {
        counts = null;
        maxNanos = Math.max(maxNanos, other.maxNanos);
    }

    long completed() {
        if (buckets == null) {
            return countIn(first) + countIn(second);
        }
        var completed = 0L;
        for (var bucket = 0; bucket < BUCKETS; bucket++) {
            completed += count(bucket);
        }
        return completed;
    }

    Counts counts() {
        if (counts == null) {
            counts = new Counts(started, entered, failed, readHistogram());
        }
        return counts;
    }

    LatencyHistogram histogram() {
        return counts().latency();
    }

    private LatencyHistogram readHistogram() {
        if (buckets == null && first == 0 && second == 0) {
            return Counts.NONE.latency();
        }
        var counts = new int[BUCKETS];
        for (var bucket = 0; bucket < BUCKETS; bucket++) {
            counts[bucket] = count(bucket);
        }
        return new LatencyHistogram(new IntList(counts), Duration.ofNanos(totalNanos), Duration.ofNanos(maxNanos));
    }

    // Adds the other cell's counts, times the sign, bucket by bucket in their order; an inline histogram holds at most two buckets. True when
    // the histogram became an array.
    private boolean addBuckets(Cell other, int sign) {
        var promoted = false;
        if (other.buckets != null) {
            for (var bucket = 0; bucket < BUCKETS; bucket++) {
                if (other.buckets[bucket] > 0) {
                    promoted |= addToBucket(bucket, sign * other.buckets[bucket]);
                }
            }
            return promoted;
        }
        var lower = bucketIn(other.second) < bucketIn(other.first) ? other.second : other.first;
        var upper = lower == other.first ? other.second : other.first;
        if (lower != 0) {
            promoted |= addToBucket(bucketIn(lower), sign * countIn(lower));
        }
        if (upper != 0) {
            promoted |= addToBucket(bucketIn(upper), sign * countIn(upper));
        }
        return promoted;
    }

    private int count(int bucket) {
        if (buckets != null) {
            return buckets[bucket];
        }
        if (bucketIn(first) == bucket) {
            return countIn(first);
        }
        return bucketIn(second) == bucket ? countIn(second) : 0;
    }

    // Adds the count, which may be negative, to the bucket; true when the histogram became an array.
    private boolean addToBucket(int bucket, int count) {
        if (buckets != null) {
            buckets[bucket] += count;
            return false;
        }
        if (bucketIn(first) == bucket && countIn(first) + count <= MAX_INLINE_COUNT) {
            first = slot(bucket, countIn(first) + count);
            return false;
        }
        if (bucketIn(second) == bucket && countIn(second) + count <= MAX_INLINE_COUNT) {
            second = slot(bucket, countIn(second) + count);
            return false;
        }
        if (first == 0 && count > 0 && count <= MAX_INLINE_COUNT && bucketIn(second) != bucket) {
            first = slot(bucket, count);
            return false;
        }
        if (second == 0 && count > 0 && count <= MAX_INLINE_COUNT && bucketIn(first) != bucket) {
            second = slot(bucket, count);
            return false;
        }
        buckets = new int[BUCKETS];
        if (first != 0) {
            buckets[bucketIn(first)] = countIn(first);
        }
        if (second != 0) {
            buckets[bucketIn(second)] = countIn(second);
        }
        first = 0;
        second = 0;
        buckets[bucket] += count;
        return true;
    }

    private static int slot(int bucket, int count) {
        return count == 0 ? 0 : (bucket + 1) << COUNT_BITS | count;
    }

    private static int bucketIn(int slot) {
        return slot == 0 ? -1 : (slot >>> COUNT_BITS) - 1;
    }

    private static int countIn(int slot) {
        return slot & MAX_INLINE_COUNT;
    }

    private static int bucketOf(long nanos) {
        var bucket = 0;
        while (bucket < BOUNDS_NANOS.length && nanos > BOUNDS_NANOS[bucket]) {
            bucket++;
        }
        return bucket;
    }
}
