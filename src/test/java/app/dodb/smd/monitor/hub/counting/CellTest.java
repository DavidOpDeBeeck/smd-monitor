package app.dodb.smd.monitor.hub.counting;

import app.dodb.smd.monitor.hub.LatencyHistogram;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CellTest {

    // In the buckets up to 0.1 ms, up to 1 ms, up to 10 ms, and up to 100 ms.
    private static final long FAST = Duration.ofNanos(50_000).toNanos();
    private static final long ONE_MILLI = Duration.ofMillis(1).toNanos();
    private static final long TEN_MILLIS = Duration.ofMillis(10).toNanos();
    private static final long HUNDRED_MILLIS = Duration.ofMillis(100).toNanos();

    @Test
    void countDuration_twoBuckets_keepsThemInlineWithEveryCount() {
        var cell = new Cell();

        var promoted = List.of(cell.countDuration(FAST), cell.countDuration(FAST), cell.countDuration(TEN_MILLIS));

        assertThat(promoted).containsOnly(false);
        assertThat(cell.histogram().counts()).isEqualTo(counts(0, 2, 6, 1));
        assertThat(cell.histogram().total()).isEqualTo(Duration.ofNanos(2 * FAST + TEN_MILLIS));
        assertThat(cell.histogram().max()).isEqualTo(Duration.ofNanos(TEN_MILLIS));
        assertThat(cell.completed()).isEqualTo(3);
    }

    @Test
    void countDuration_thirdBucket_promotesToEveryBucketKeepingTheCounts() {
        var cell = new Cell();
        cell.countDuration(FAST);
        cell.countDuration(TEN_MILLIS);

        var promoted = cell.countDuration(HUNDRED_MILLIS);

        assertThat(promoted).isTrue();
        assertThat(cell.histogram().counts()).isEqualTo(counts(0, 1, 6, 1, 9, 1));
        assertThat(cell.countDuration(ONE_MILLI)).isFalse();
        assertThat(cell.completed()).isEqualTo(4);
    }

    @Test
    void add_cellsWithThreeBucketsBetweenThem_promotesAndSumsTheirCounts() {
        var first = new Cell();
        first.countInvocation(true, false, FAST);
        first.countInvocation(false, true, TEN_MILLIS);
        var second = new Cell();
        second.countInvocation(false, false, HUNDRED_MILLIS);
        second.countInvocation(false, false, FAST);
        var total = new Cell();
        total.add(first);

        var promoted = total.add(second);

        assertThat(promoted).isTrue();
        assertThat(total.counts()).satisfies(counts -> {
            assertThat(counts.started()).isEqualTo(4);
            assertThat(counts.entered()).isEqualTo(1);
            assertThat(counts.failed()).isEqualTo(1);
            assertThat(counts.latency().counts()).isEqualTo(counts(0, 2, 6, 1, 9, 1));
        });
    }

    @Test
    void subtract_cellAddedEarlier_leavesTheCountsOfTheOther() {
        var leaving = new Cell();
        leaving.countDuration(FAST);
        leaving.countDuration(HUNDRED_MILLIS);
        var staying = new Cell();
        staying.countDuration(TEN_MILLIS);
        var total = new Cell();
        total.add(leaving);
        total.add(staying);

        var heldMaximum = total.subtract(leaving);

        assertThat(heldMaximum).isTrue();
        assertThat(total.histogram().counts()).isEqualTo(staying.histogram().counts());
        assertThat(total.histogram().total()).isEqualTo(Duration.ofNanos(TEN_MILLIS));
    }

    @Test
    void subtract_everyCount_leavesAnEmptyHistogram() {
        var cell = new Cell();
        cell.countDuration(FAST);
        var total = new Cell();
        total.add(cell);

        total.subtract(cell);

        assertThat(total.completed()).isZero();
        assertThat(total.histogram().max()).isEqualTo(Duration.ZERO);
        assertThat(total.countDuration(TEN_MILLIS)).isFalse();
        assertThat(total.completed()).isEqualTo(1);
    }

    // The counts per bucket, as alternating bucket indexes and counts.
    private static List<Long> counts(int... bucketsAndCounts) {
        var counts = new Long[LatencyHistogram.BOUNDS.size() + 1];
        Arrays.fill(counts, 0L);
        for (var index = 0; index < bucketsAndCounts.length; index += 2) {
            counts[bucketsAndCounts[index]] = (long) bucketsAndCounts[index + 1];
        }
        return List.of(counts);
    }
}
