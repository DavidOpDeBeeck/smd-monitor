package app.dodb.smd.monitor.hub;

import app.dodb.smd.monitor.hub.RecordingInstance.MutableClock;
import app.dodb.smd.monitor.hub.health.HealthReason;
import app.dodb.smd.monitor.hub.intake.SpanBatch;
import app.dodb.smd.monitor.hub.span.MessageKind;
import app.dodb.smd.monitor.hub.span.MessageSpan;
import app.dodb.smd.monitor.hub.span.MessageType;
import app.dodb.smd.monitor.hub.span.Outcome;
import app.dodb.smd.monitor.hub.span.Stage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.SplittableRandom;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

// The counts of generated load equal a recount of the same spans by the definitions of docs/monitor-hub-design.md, whatever order the spans
// arrive in and however late: every count in the start second of its span (T1), the window and history ending settle seconds before the clock
// (T2), and a span arriving late revising its own second (T3).
class CountingRecountTest {

    private static final LoadProfile PROFILE = new LoadProfile(3, 2, 15, 4, 300, Duration.ofSeconds(20), Duration.ofSeconds(60), HubConfig.DEFAULT_MEMORY);
    private static final int SECONDS = 50;

    private final MutableClock clock = new MutableClock();

    @Test
    void statistics_generatedLoadReceivedAsExported_equalTheRecount() {
        var exports = exported(5);
        var hub = hub();

        exports.forEach(export -> {
            clock.advance(Duration.between(clock.instant(), export.at()));
            export.batches().forEach(hub::receive);
        });

        assertCountsEqualTheRecount(hub, exports);
    }

    @Test
    void statistics_generatedLoadShuffledAndReceivedAtTheEnd_equalTheRecount() {
        var exports = exported(6);
        var batches = new ArrayList<>(exports.stream().flatMap(export -> export.batches().stream()).toList());
        Collections.shuffle(batches, new Random(6));
        var hub = hub();
        clock.advance(Duration.between(clock.instant(), exports.getLast().at()));

        batches.forEach(hub::receive);

        assertCountsEqualTheRecount(hub, exports);
    }

    @Test
    void statistics_generatedLoadWithEveryThirdBatchLate_equalTheRecount() {
        var exports = exported(7);
        var random = new SplittableRandom(7);
        var late = new ArrayList<SpanBatch>();
        var hub = hub();

        for (var export : exports) {
            clock.advance(Duration.between(clock.instant(), export.at()));
            hub.statistics();
            for (var batch : export.batches()) {
                if (random.nextInt(3) == 0) {
                    late.add(batch);
                } else {
                    hub.receive(batch);
                }
            }
        }
        late.reversed().forEach(hub::receive);

        assertThat(late).isNotEmpty();
        assertCountsEqualTheRecount(hub, exports);
        assertThat(hub.health().applications()).anySatisfy(application -> assertThat(application.count(HealthReason.REVISED)).isPositive());
    }

    private void assertCountsEqualTheRecount(SynchronousHub hub, List<Export> exports) {
        var spans = exports.stream().flatMap(export -> export.batches().stream()).flatMap(batch -> batch.spans().stream().map(span -> new Received(batch, span)))
            .toList();
        var statistics = hub.statistics();
        var end = statistics.end().getEpochSecond();
        var window = Range.of(end - statistics.window().toSeconds(), end);
        var history = Range.of(end - statistics.history().toSeconds(), end);

        assertThat(statistics.applications()).isNotEmpty().allSatisfy(application -> {
            var name = application.application();
            var own = spans.stream().filter(span -> span.batch().application().equals(name)).toList();
            assertThat(application.types()).isNotEmpty().allSatisfy(type -> {
                Predicate<Received> dispatches = span -> span.stage() instanceof Stage.Dispatch && span.type().equals(type.type());
                assertThat(type.window()).isEqualTo(recount(own, dispatches, window));
                assertThat(type.history()).isEqualTo(recount(own, dispatches, history));
                assertThat(type.startedPerSecond()).isEqualTo(perSecond(own, dispatches, window, span -> true));
                assertThat(type.failedPerSecond()).isEqualTo(perSecond(own, dispatches, window, Received::failed));
            });
            assertThat(application.groups()).allSatisfy(group -> assertThat(group.handlings()).allSatisfy(handling -> {
                Predicate<Received> handlings = span -> span.stage().equals(new Stage.Handling(handling.type(), group.processingGroup()));
                assertThat(handling.window()).isEqualTo(recount(own, handlings, window));
                assertThat(handling.history()).isEqualTo(recount(own, handlings, history));
            }));
            assertThat(application.handlings()).isEqualTo(recount(own, span -> span.stage() instanceof Stage.Handling, window));
            for (var kind : MessageKind.values()) {
                Predicate<Received> dispatchesOfKind = span -> span.stage() instanceof Stage.Dispatch && span.type().kind() == kind;
                assertThat(application.kinds().get(kind)).isEqualTo(recount(own, dispatchesOfKind, window));
                assertThat(application.startedPerSecond().get(kind)).isEqualTo(perSecond(own, dispatchesOfKind, history, span -> true));
            }
            assertThat(application.enteredPerSecond()).isEqualTo(perSecond(own, Received::entryPoint, window, span -> true));
            assertThat(application.failedPerSecond()).isEqualTo(perSecond(own, span -> true, history, Received::failed));
            assertThat(application.instances()).isNotEmpty().allSatisfy(instance -> {
                Predicate<Received> ofInstance = span -> span.batch().instance().equals(instance.name());
                assertThat(instance.dispatches()).isEqualTo(recount(own, ofInstance.and(span -> span.stage() instanceof Stage.Dispatch), window));
                assertThat(instance.handlings()).isEqualTo(recount(own, ofInstance.and(span -> span.stage() instanceof Stage.Handling), window));
            });
            assertThat(application.failures().stream().mapToLong(RaisedFailureStatistics::history).sum())
                .isEqualTo(own.stream().filter(span -> history.contains(span.second()) && span.failed()).count());
        });
    }

    private static Counts recount(List<Received> spans, Predicate<Received> matching, Range range) {
        return spans.stream()
            .filter(matching)
            .filter(span -> range.contains(span.second()))
            .map(Received::counts)
            .reduce(Counts.NONE, Counts::plus);
    }

    private static List<Long> perSecond(List<Received> spans, Predicate<Received> matching, Range range, Predicate<Received> counted) {
        Map<Long, Long> bySecond = spans.stream().filter(matching).filter(counted).collect(Collectors.groupingBy(Received::second, HashMap::new, Collectors.counting()));
        return LongStream.range(range.from(), range.end()).mapToObj(second -> bySecond.getOrDefault(second, 0L)).toList();
    }

    private SynchronousHub hub() {
        return new SynchronousHub(PROFILE.hubConfig().build(), clock);
    }

    // What the generator exported each second, with the instant it exported it at; the clock is rewound to where it started.
    private List<Export> exported(long seed) {
        var started = clock.instant();
        var load = new LoadGenerator(PROFILE, seed, clock);
        var exports = new ArrayList<Export>();
        for (var second = 0; second < SECONDS; second++) {
            clock.advance(Duration.ofSeconds(1));
            exports.add(new Export(clock.instant(), load.export()));
        }
        clock.advance(Duration.between(clock.instant(), started));
        return exports;
    }

    private record Export(Instant at, List<SpanBatch> batches) {
    }

    private record Range(long from, long end) {

        static Range of(long from, long end) {
            return new Range(from, end);
        }

        boolean contains(long second) {
            return second >= from && second < end;
        }
    }

    private record Received(SpanBatch batch, MessageSpan span) {

        long second() {
            return span.startedAt().getEpochSecond();
        }

        Stage stage() {
            return span.stage();
        }

        MessageType type() {
            return span.stage().type();
        }

        boolean failed() {
            return span.outcome() instanceof Outcome.Failed;
        }

        boolean entryPoint() {
            return span.entryPoint();
        }

        Counts counts() {
            return new Counts(1, entryPoint() ? 1 : 0, failed() ? 1 : 0, LatencyHistogram.of(span.outcome().duration()));
        }
    }
}
