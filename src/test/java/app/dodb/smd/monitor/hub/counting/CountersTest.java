package app.dodb.smd.monitor.hub.counting;

import app.dodb.smd.monitor.hub.HubConfig;
import app.dodb.smd.monitor.hub.span.MessageKind;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;

class CountersTest {

    // A multiple of 10 seconds, so the 10-second buckets start at it.
    private static final long BASE = 1_767_268_800L;
    private static final int STAGE = 4;
    private static final int INSTANCE = 0;
    private static final HubConfig CONFIG = HubConfig.withDefaults().window(Duration.ofSeconds(10)).history(Duration.ofSeconds(60)).build();

    @Test
    void countInvocation_secondThatSettled_countsItsLatencyAndFailureInItsStartSecond() {
        var counters = countersAt(BASE + 10);

        counters.countInvocation(dispatch(BASE, Duration.ofSeconds(3), true));

        var window = counters.window(Table.STAGE, STAGE);
        assertThat(window).extracting(counts -> counts.started(), counts -> counts.failed(), counts -> counts.completed()).containsExactly(1L, 1L, 1L);
        assertThat(window.latency().max()).isEqualTo(Duration.ofSeconds(3));
        assertThat(counters.revised()).isEqualTo(1);
    }

    @Test
    void advance_secondThatHadNotSettled_entersTheWindowAndHistoryOnceItSettles() {
        var counters = countersAt(BASE + 3);
        counters.countInvocation(dispatch(BASE, Duration.ofMillis(1), false));
        var unsettled = counters.window(Table.STAGE, STAGE).started();

        counters.advance(Instant.ofEpochSecond(BASE + 6));

        assertThat(unsettled).isZero();
        assertThat(counters.window(Table.STAGE, STAGE).started()).isEqualTo(1);
        assertThat(counters.history(Table.STAGE, STAGE).started()).isEqualTo(1);
        assertThat(counters.revised()).isZero();
    }

    @Test
    void advance_secondLeavingTheWindow_staysInTheHistory() {
        var counters = countersAt(BASE + 10);
        counters.countInvocation(dispatch(BASE, Duration.ofMillis(1), false));

        counters.advance(Instant.ofEpochSecond(BASE + 16));

        assertThat(counters.window(Table.STAGE, STAGE).started()).isZero();
        assertThat(counters.history(Table.STAGE, STAGE).started()).isEqualTo(1);
    }

    @Test
    void advance_secondLeavingTheHistory_leavesItWithItsTenSecondBucket() {
        var counters = countersAt(BASE + 10);
        counters.countInvocation(dispatch(BASE + 3, Duration.ofMillis(1), false));

        // The history starts at BASE + 4, after the counted second, but its bucket of BASE to BASE + 10 still counts.
        counters.advance(Instant.ofEpochSecond(BASE + 69));
        var bucketInHistory = counters.history(Table.STAGE, STAGE).started();
        counters.advance(Instant.ofEpochSecond(BASE + 75));

        assertThat(bucketInHistory).isEqualTo(1);
        assertThat(counters.history(Table.STAGE, STAGE).started()).isZero();
    }

    @Test
    void countInvocation_secondBeforeTheHistory_isNotCountedButReported() {
        var counters = countersAt(BASE + 100);

        counters.countInvocation(dispatch(BASE + 34, Duration.ofMillis(1), false));

        assertThat(counters.history(Table.STAGE, STAGE).started()).isZero();
        assertThat(counters.beyondHistory()).isEqualTo(1);
    }

    @Test
    void countInvocation_lateSpanForASecondInItsBucket_revisesTheHistory() {
        var counters = countersAt(BASE + 10);
        counters.advance(Instant.ofEpochSecond(BASE + 60));

        counters.countInvocation(dispatch(BASE + 2, Duration.ofMillis(1), false));

        assertThat(counters.history(Table.STAGE, STAGE).started()).isEqualTo(1);
        assertThat(counters.window(Table.STAGE, STAGE).started()).isZero();
        assertThat(counters.revised()).isEqualTo(1);
    }

    @Test
    void window_slowestSecondLeftTheWindow_reportsTheSlowestThatRemains() {
        var counters = countersAt(BASE + 10);
        counters.countInvocation(dispatch(BASE, Duration.ofMillis(9), false));
        counters.countInvocation(dispatch(BASE + 1, Duration.ofMillis(2), false));

        counters.advance(Instant.ofEpochSecond(BASE + 16));

        assertThat(counters.window(Table.STAGE, STAGE).latency().max()).isEqualTo(Duration.ofMillis(2));
        assertThat(counters.history(Table.STAGE, STAGE).latency().max()).isEqualTo(Duration.ofMillis(9));
    }

    @Test
    void startedPerSecond_dispatchesOfTheWindow_endWithTheLastSettledSecond() {
        var counters = countersAt(BASE + 15);
        counters.countInvocation(dispatch(BASE + 9, Duration.ofMillis(1), true));
        counters.countInvocation(dispatch(BASE + 9, Duration.ofMillis(1), false));
        counters.countInvocation(dispatch(BASE + 10, Duration.ofMillis(1), false));

        var started = counters.startedPerSecond(STAGE);

        assertThat(started).hasSize(10).endsWith(2L);
        assertThat(counters.failedPerSecond(STAGE)).endsWith(1L);
        assertThat(counters.startedPerSecond(MessageKind.COMMAND)).hasSize(60).endsWith(2L);
        assertThat(counters.failedPerSecond()).hasSize(60).endsWith(1L);
        assertThat(counters.enteredPerSecond()).isEqualTo(Collections.nCopies(10, 0L));
    }

    @Test
    void startedPerSecond_windowWrappingAroundTheRing_keepsEverySecondInOrder() {
        var counters = countersAt(BASE + 15);
        var started = new ArrayList<Long>();
        var failed = new ArrayList<Long>();
        var series = new ArrayList<List<Long>>();

        // Twice around the ring of the window, the settle, and the seconds ahead, a second at a time, each counting in the window's last second.
        for (var step = 0; step < 100; step++) {
            counters.advance(Instant.ofEpochSecond(BASE + 15 + step));
            var second = counters.end() - 1;
            var dispatches = Math.floorMod(second, 3) + 1;
            for (var dispatch = 0; dispatch < dispatches; dispatch++) {
                counters.countInvocation(dispatch(second, Duration.ofMillis(1), dispatch == 0 && second % 2 == 0));
            }
            started.add((long) dispatches);
            failed.add(second % 2 == 0 ? 1L : 0L);
            series.add(counters.startedPerSecond(STAGE));
            series.add(counters.failedPerSecond(STAGE));
        }

        for (var step = 10; step < 100; step++) {
            assertThat(series.get(2 * step)).as("started at step %d", step).isEqualTo(started.subList(step - 9, step + 1));
            assertThat(series.get(2 * step + 1)).as("failed at step %d", step).isEqualTo(failed.subList(step - 9, step + 1));
        }
    }

    @Test
    void windowPerSecond_occurrencesOfSeveralKeys_countEachInItsSecondOfTheWindow() {
        var counters = countersAt(BASE + 10);
        counters.countOccurrence(Table.FAILURE, 1, Instant.ofEpochSecond(BASE));
        counters.countOccurrence(Table.FAILURE, 1, Instant.ofEpochSecond(BASE + 2));
        counters.countOccurrence(Table.FAILURE, 1, Instant.ofEpochSecond(BASE + 2));
        counters.countOccurrence(Table.FAILURE, 2, Instant.ofEpochSecond(BASE + 9));
        counters.countOccurrence(Table.FAILURE, 3, Instant.ofEpochSecond(BASE));
        // The window runs from BASE + 1 to BASE + 11: the counts of BASE left it, and BASE + 9 settled into it.
        counters.advance(Instant.ofEpochSecond(BASE + 16));

        var perSecond = counters.windowPerSecond(Table.FAILURE, 5);

        assertThat(perSecond).hasSize(5);
        assertThat(perSecond.get(1)).containsExactly(0L, 2L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
        assertThat(perSecond.get(2)).containsExactly(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 1L, 0L);
        assertThat(perSecond.get(3)).isEmpty();
        assertThat(perSecond.get(4)).isEmpty();
    }

    @Test
    void lastSecond_occurrencesOfTheLastSecondOfTheWindow_countThem() {
        var counters = countersAt(BASE + 15);

        counters.countOccurrence(Table.LINK, 1, Instant.ofEpochSecond(BASE + 9));
        counters.countOccurrence(Table.LINK, 1, Instant.ofEpochSecond(BASE + 8));

        assertThat(counters.lastSecond(Table.LINK, 1)).isEqualTo(1);
        assertThat(counters.historyCount(Table.LINK, 1)).isEqualTo(2);
    }

    @Test
    void provisionalFrom_spansArrivingEightSecondsLate_marksTheSecondsTheyCanStillReach() {
        var counters = countersAt(BASE + 30);

        counters.recordArrival(Instant.ofEpochSecond(BASE + 30), Instant.ofEpochSecond(BASE + 22), Duration.ofSeconds(1), INSTANCE);

        assertThat(counters.provisionalFrom()).isEqualTo(BASE + 22);
        assertThat(counters.exportLag(INSTANCE)).hasValueSatisfying(lag -> assertThat(lag).isBetween(Duration.ofSeconds(5), Duration.ofSeconds(10)));
    }

    @Test
    void provisionalFrom_noArrivals_isTheEndOfTheWindow() {
        var counters = countersAt(BASE + 30);

        assertThat(counters.provisionalFrom()).isEqualTo(counters.end());
        assertThat(counters.exportLag(INSTANCE)).isEmpty();
    }

    @Test
    void advance_gapLongerThanEverythingKept_startsAfresh() {
        var counters = countersAt(BASE + 10);
        counters.countInvocation(dispatch(BASE, Duration.ofMillis(1), true));

        counters.advance(Instant.ofEpochSecond(BASE + 3_600));

        assertThat(counters.history(Table.STAGE, STAGE).started()).isZero();
        assertThat(counters.startedPerSecond(STAGE)).containsOnly(0L);
        assertThat(counters.end()).isEqualTo(BASE + 3_595);
    }

    @Test
    void window_keyUnchangedSinceTheLastRead_isTheCountsReadThen() {
        var counters = countersAt(BASE + 10);
        counters.countInvocation(dispatch(BASE + 2, Duration.ofMillis(1), false));
        var read = counters.window(Table.STAGE, STAGE);

        counters.advance(Instant.ofEpochSecond(BASE + 11));

        assertThat(counters.window(Table.STAGE, STAGE)).isSameAs(read);
        assertThat(read.started()).isEqualTo(1);
    }

    @Test
    void window_keyCountedSinceTheLastRead_countsTheNewInvocation() {
        var counters = countersAt(BASE + 10);
        counters.countInvocation(dispatch(BASE + 2, Duration.ofMillis(1), false));
        counters.window(Table.STAGE, STAGE);

        counters.countInvocation(dispatch(BASE + 3, Duration.ofMillis(4), true));

        var window = counters.window(Table.STAGE, STAGE);
        assertThat(window).extracting(counts -> counts.started(), counts -> counts.failed()).containsExactly(2L, 1L);
        assertThat(window.latency().max()).isEqualTo(Duration.ofMillis(4));
    }

    @Test
    void window_secondLeftTheWindowSinceTheLastRead_leavesItsCounts() {
        var counters = countersAt(BASE + 10);
        counters.countInvocation(dispatch(BASE, Duration.ofMillis(1), false));
        counters.window(Table.STAGE, STAGE);

        counters.advance(Instant.ofEpochSecond(BASE + 16));

        assertThat(counters.window(Table.STAGE, STAGE).started()).isZero();
    }

    @Test
    void window_slowestSecondLeftTheWindowSinceTheLastRead_reportsTheSlowestThatRemains() {
        var counters = countersAt(BASE + 10);
        counters.countInvocation(dispatch(BASE, Duration.ofMillis(9), false));
        counters.countInvocation(dispatch(BASE + 1, Duration.ofMillis(2), false));
        counters.window(Table.STAGE, STAGE);

        counters.advance(Instant.ofEpochSecond(BASE + 16));

        assertThat(counters.window(Table.STAGE, STAGE).latency().max()).isEqualTo(Duration.ofMillis(2));
    }

    @Test
    void history_secondSettledSinceTheLastRead_countsIt() {
        var counters = countersAt(BASE + 3);
        counters.countInvocation(dispatch(BASE, Duration.ofMillis(1), false));
        var unsettled = counters.history(Table.STAGE, STAGE);

        counters.advance(Instant.ofEpochSecond(BASE + 6));

        assertThat(unsettled.started()).isZero();
        assertThat(counters.history(Table.STAGE, STAGE).started()).isEqualTo(1);
    }

    @Test
    void windowLatency_delayCountedSinceTheLastRead_countsIt() {
        var counters = countersAt(BASE + 10);
        counters.countDelay(STAGE, Instant.ofEpochSecond(BASE + 2), Duration.ofMillis(5));
        counters.windowLatency(Table.DELAY, STAGE);

        counters.countDelay(STAGE, Instant.ofEpochSecond(BASE + 3), Duration.ofMillis(7));

        var latency = counters.windowLatency(Table.DELAY, STAGE);
        assertThat(latency.count()).isEqualTo(2);
        assertThat(latency.max()).isEqualTo(Duration.ofMillis(7));
    }

    @Test
    void failedPerSecond_dispatchesThatAllCompleted_isEverySecondZero() {
        var counters = countersAt(BASE + 10);
        counters.countInvocation(dispatch(BASE + 2, Duration.ofMillis(1), false));

        var failed = counters.failedPerSecond(STAGE);

        assertThat(failed).hasSize(10).containsOnly(0L);
        assertThat(counters.startedPerSecond(STAGE)).hasSize(10).containsExactly(0L, 0L, 0L, 0L, 0L, 0L, 0L, 1L, 0L, 0L);
    }

    @Test
    void failedPerSecond_failureAfterCompletedDispatches_countsOnlyTheFailure() {
        var counters = countersAt(BASE + 10);
        counters.countInvocation(dispatch(BASE + 1, Duration.ofMillis(1), false));
        counters.countInvocation(dispatch(BASE + 2, Duration.ofMillis(1), false));

        counters.countInvocation(dispatch(BASE + 3, Duration.ofMillis(1), true));

        assertThat(counters.failedPerSecond(STAGE)).containsExactly(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 1L, 0L);
        assertThat(counters.startedPerSecond(STAGE)).containsExactly(0L, 0L, 0L, 0L, 0L, 0L, 1L, 1L, 1L, 0L);
    }

    private static Counters countersAt(long second) {
        return new Counters(CONFIG, Instant.ofEpochSecond(second));
    }

    private static CountedInvocation dispatch(long second, Duration duration, boolean failed) {
        return new CountedInvocation(Instant.ofEpochSecond(second), duration, STAGE, Optional.of(MessageKind.COMMAND), INSTANCE, false,
            failed ? OptionalInt.of(1) : OptionalInt.empty());
    }
}
