package app.dodb.smd.monitor.hub;

import app.dodb.smd.api.event.delivery.EventDelivery;
import app.dodb.smd.monitor.hub.span.MessageSpan;
import app.dodb.smd.monitor.hub.span.Outcome;
import app.dodb.smd.monitor.hub.span.Stage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static app.dodb.smd.monitor.hub.RecordingInstance.OPEN;
import static app.dodb.smd.monitor.hub.RecordingInstance.OPENED;
import static app.dodb.smd.monitor.hub.RecordingInstance.TICKETS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StatisticsTest {

    @Test
    void bucketOf_durationAtABound_countsInThatBucket() {
        assertThat(LatencyHistogram.bucketOf(Duration.ZERO)).isZero();
        assertThat(LatencyHistogram.bucketOf(Duration.ofMillis(1))).isEqualTo(3);
        assertThat(LatencyHistogram.bucketOf(Duration.ofMillis(1).plusNanos(1))).isEqualTo(4);
        assertThat(LatencyHistogram.bucketOf(Duration.ofSeconds(60))).isEqualTo(LatencyHistogram.BOUNDS.size() - 1);
        assertThat(LatencyHistogram.bucketOf(Duration.ofSeconds(61))).isEqualTo(LatencyHistogram.BOUNDS.size());
    }

    @Test
    void plus_twoHistograms_sumsCountsAndTotalAndKeepsTheLongestMax() {
        var sum = LatencyHistogram.of(Duration.ofMillis(4)).plus(LatencyHistogram.of(Duration.ofMillis(3)));

        assertThat(sum.counts().get(LatencyHistogram.bucketOf(Duration.ofMillis(4)))).isEqualTo(2);
        assertThat(sum.total()).isEqualTo(Duration.ofMillis(7));
        assertThat(sum.max()).isEqualTo(Duration.ofMillis(4));
    }

    @Test
    void percentile_withinABucket_interpolatesUpToTheSlowestInvocation() {
        var latency = histogram(Duration.ofMillis(6), Duration.ofMillis(7), Duration.ofMillis(8), Duration.ofMillis(9));

        assertThat(latency.percentile(0.5)).contains(Duration.ofNanos(7_000_000));
        assertThat(latency.percentile(1)).contains(Duration.ofMillis(9));
        assertThat(latency.average()).contains(Duration.ofNanos(7_500_000));
        assertThat(latency.floor()).contains(Duration.ofMillis(5));
    }

    @Test
    void percentile_beyondTheLastBound_endsAtTheSlowestInvocation() {
        var latency = histogram(Duration.ofSeconds(20));

        assertThat(latency.percentile(0.95)).contains(Duration.ofNanos(19_500_000_000L));
    }

    @Test
    void percentile_nothingCompleted_isEmpty() {
        assertThat(LatencyHistogram.EMPTY.percentile(0.5)).isEmpty();
        assertThat(LatencyHistogram.EMPTY.average()).isEmpty();
        assertThat(LatencyHistogram.EMPTY.floor()).isEmpty();
    }

    @Test
    void floor_fastestBucket_startsAtHalfItsBound() {
        assertThat(histogram(Duration.ofNanos(1)).floor()).contains(Duration.ofNanos(50_000));
    }

    @Test
    void new_histogramWithWrongBucketCount_rejectsWithContext() {
        assertThatThrownBy(() -> new LatencyHistogram(List.of(1L), Duration.ZERO, Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("19 nonnegative bucket counts", "[1]");
    }

    @Test
    void new_countsWithMoreEnteredThanStarted_rejectsWithContext() {
        assertThatThrownBy(() -> new Counts(1, 2, 0, LatencyHistogram.EMPTY))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("started 1", "entered 2");
    }

    @Test
    void new_countsWithMoreFailedThanCompleted_rejectsWithContext() {
        assertThatThrownBy(() -> new Counts(1, 0, 1, LatencyHistogram.EMPTY))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("failed 1", "completed 0");
    }

    @Test
    void plus_twoCounts_sumsEach() {
        var counts = new Counts(1, 1, 0, LatencyHistogram.EMPTY).plus(new Counts(0, 0, 1, LatencyHistogram.of(Duration.ofMillis(1))));

        assertThat(counts).extracting(Counts::started, Counts::entered, Counts::failed, Counts::completed).containsExactly(1L, 1L, 1L, 1L);
    }

    @Test
    void new_handlingOfACommandInAProcessingGroup_rejectsNamingTheGroupAndType() {
        assertThatThrownBy(() -> new Stage.Handling(OPEN, Optional.of("ticket-view")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("'ticket-view'", "none for a command or query", "OpenTicket");
    }

    @Test
    void new_handlingOfAnEventWithoutAProcessingGroup_rejectsNamingTheType() {
        assertThatThrownBy(() -> new Stage.Handling(OPENED, Optional.empty()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("requires a processing group for an event", "TicketOpened");
    }

    @Test
    void new_handlingOfACommandWithoutAProcessingGroup_isAHandlingOfItsSingleHandler() {
        var handling = new Stage.Handling(OPEN, Optional.empty());

        assertThat(handling.processingGroup()).isEmpty();
    }

    @Test
    void new_spanOfAGroupHandlingWithoutADelivery_rejectsNamingTheSpanAndType() {
        var instance = new RecordingInstance(TICKETS, "tickets-1");
        var handling = instance.handle("ticket-view", instance.dispatch(OPENED));

        assertThatThrownBy(() -> withDelivery(handling, Optional.empty()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(handling.span().spanId(), "TicketOpened", "requires a delivery for a processing group's handling", "no delivery");
    }

    @Test
    void new_spanOfADispatchWithADelivery_rejectsNamingTheDelivery() {
        var dispatch = new RecordingInstance(TICKETS, "tickets-1").dispatch(OPENED);

        assertThatThrownBy(() -> withDelivery(dispatch, Optional.of(EventDelivery.SYNC)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(dispatch.span().spanId(), "none otherwise", "delivery 'sync'");
    }

    private static MessageSpan withDelivery(MessageSpan span, Optional<EventDelivery> delivery) {
        return new MessageSpan(span.span(), span.continues(), span.messageId(), span.stage(), delivery, span.startedAt(), span.threadName(), span.outcome());
    }

    private static LatencyHistogram histogram(Duration... durations) {
        var latency = LatencyHistogram.EMPTY;
        for (var duration : durations) {
            latency = latency.plus(LatencyHistogram.of(duration));
        }
        return latency;
    }
}
