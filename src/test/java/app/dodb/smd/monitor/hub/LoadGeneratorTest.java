package app.dodb.smd.monitor.hub;

import app.dodb.smd.monitor.hub.RecordingInstance.MutableClock;
import app.dodb.smd.monitor.hub.intake.SpanBatch;
import app.dodb.smd.monitor.hub.span.ApplicationName;
import app.dodb.smd.monitor.hub.span.MessageSpan;
import app.dodb.smd.monitor.hub.span.Outcome;
import app.dodb.smd.monitor.hub.span.SinkSend;
import app.dodb.smd.monitor.hub.span.SpanRef;
import app.dodb.smd.monitor.hub.span.Stage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class LoadGeneratorTest {

    private static final LoadProfile PROFILE = new LoadProfile(3, 2, 15, 4, 200, Duration.ofMinutes(1), Duration.ofMinutes(5), HubConfig.DEFAULT_MEMORY);

    @Test
    void export_sameSeedAndClockSteps_givesTheSameReports() {
        var first = exportFor(PROFILE, 7, 20);

        var second = exportFor(PROFILE, 7, 20);

        assertThat(second).isEqualTo(first);
    }

    @Test
    void export_differentSeeds_giveDifferentReports() {
        var first = exportFor(PROFILE, 7, 20);

        var second = exportFor(PROFILE, 8, 20);

        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void export_profileRate_startsAboutThatManySpansPerSecond() {
        var clock = new MutableClock();
        var from = clock.instant().getEpochSecond();

        var reports = exportFor(PROFILE, 7, 70, clock);

        var started = reports.stream().flatMap(report -> report.spans().stream()).filter(span -> span.startedAt().getEpochSecond() - from < 50).count()
            + reports.stream().flatMap(report -> report.sends().stream()).filter(send -> send.startedAt().getEpochSecond() - from < 50).count();
        assertThat(started / 50.0).isCloseTo(PROFILE.spansPerSecond(), within(PROFILE.spansPerSecond() * 0.1));
    }

    @Test
    void export_busyInstance_splitsItsExportIntoReportsOfAtMostTheMaximumBatch() {
        var busy = new LoadProfile(1, 1, 15, 4, 2_000, Duration.ofMinutes(1), Duration.ofMinutes(5), HubConfig.DEFAULT_MEMORY);

        var reports = exportFor(busy, 7, 10);

        assertThat(reports).allSatisfy(report -> assertThat(report.size()).isBetween(1, LoadGenerator.MAX_EXPORT_BATCH));
        assertThat(reports).anySatisfy(report -> assertThat(report.size()).isEqualTo(LoadGenerator.MAX_EXPORT_BATCH));
    }

    @Test
    void export_rateOfTheSecond_startsThatManySpansInIt() {
        var clock = new MutableClock();
        var from = clock.instant().getEpochSecond();
        var load = new LoadGenerator(PROFILE, 7, clock, second -> second - from < 30 ? 100 : 1_000, 0);

        var reports = new ArrayList<SpanBatch>();
        for (var second = 0; second < 70; second++) {
            clock.advance(Duration.ofSeconds(1));
            reports.addAll(load.export());
        }

        var starts = reports.stream().flatMap(report -> Stream.concat(report.spans().stream().map(MessageSpan::startedAt),
            report.sends().stream().map(SinkSend::startedAt))).map(start -> start.getEpochSecond() - from).toList();
        assertThat(starts.stream().filter(second -> second < 30).count() / 30.0).isCloseTo(100, within(100 * 0.1));
        assertThat(starts.stream().filter(second -> second >= 30 && second < 60).count() / 30.0).isCloseTo(1_000, within(1_000 * 0.1));
    }

    @Test
    void export_firstApplication_numbersTheApplicationsFromIt() {
        var clock = new MutableClock();
        var load = new LoadGenerator(PROFILE, 7, clock, ignored -> PROFILE.spansPerSecond(), 10);

        var reports = new ArrayList<SpanBatch>();
        for (var second = 0; second < 10; second++) {
            clock.advance(Duration.ofSeconds(1));
            reports.addAll(load.export());
        }

        assertThat(reports).extracting(report -> report.application().value()).containsOnly("service-10", "service-11", "service-12");
    }

    @Test
    void export_instanceHoldingAFullBatch_exportsItBeforeItsTurn() {
        var busy = new LoadProfile(1, 1, 15, 4, 2_000, Duration.ofMinutes(1), Duration.ofMinutes(5), HubConfig.DEFAULT_MEMORY);
        var clock = new MutableClock();
        var load = new LoadGenerator(busy, 7, clock);

        var exportingSeconds = 0;
        for (var second = 0; second < 10; second++) {
            clock.advance(Duration.ofSeconds(1));
            exportingSeconds += load.export().isEmpty() ? 0 : 1;
        }

        assertThat(exportingSeconds).isGreaterThanOrEqualTo(9);
    }

    @Test
    void export_generatedLoad_crossesApplicationsDelaysHandlingsFailsAndSendsChildrenBeforeParents() {
        var reports = exportFor(PROFILE, 7, 30);

        var spans = reports.stream().flatMap(report -> report.spans().stream().map(span -> new Arrival(report.application(), span))).toList();
        var bySpan = new HashMap<SpanRef, Arrival>();
        spans.forEach(arrival -> bySpan.put(arrival.span().span(), arrival));
        assertThat(spans).anySatisfy(arrival -> assertThat(parentOf(arrival, bySpan)).hasValueSatisfying(parent ->
            assertThat(parent.application()).isNotEqualTo(arrival.application())));
        assertThat(spans).anySatisfy(arrival -> assertThat(parentOf(arrival, bySpan)).hasValueSatisfying(parent -> {
            assertThat(parent.span().stage()).isInstanceOf(Stage.Dispatch.class);
            assertThat(arrival.span().stage()).isInstanceOf(Stage.Dispatch.class);
            assertThat(arrival.span().messageId()).isEqualTo(parent.span().messageId());
        }));
        assertThat(spans).anySatisfy(arrival -> {
            assertThat(arrival.span().delivery()).contains(LoadGenerator.EVENT_STORE);
            assertThat(parentOf(arrival, bySpan)).hasValueSatisfying(parent ->
                assertThat(arrival.span().startedAt()).isAfter(parent.span().completedAt().plusMillis(20)));
        });
        assertThat(spans).anySatisfy(arrival -> assertThat(arrival.span().outcome()).isInstanceOf(Outcome.Failed.class));
        assertThat(childrenBeforeParents(spans)).isPositive();
    }

    private static Optional<Arrival> parentOf(Arrival arrival, Map<SpanRef, Arrival> bySpan) {
        return arrival.span().continues().map(bySpan::get);
    }

    private static long childrenBeforeParents(List<Arrival> spans) {
        var all = spans.stream().map(arrival -> arrival.span().span()).collect(Collectors.toSet());
        var arrived = new HashSet<SpanRef>();
        var early = 0L;
        for (var arrival : spans) {
            var parent = arrival.span().continues();
            if (parent.isPresent() && all.contains(parent.get()) && !arrived.contains(parent.get())) {
                early++;
            }
            arrived.add(arrival.span().span());
        }
        return early;
    }

    private static List<SpanBatch> exportFor(LoadProfile profile, long seed, int seconds) {
        return exportFor(profile, seed, seconds, new MutableClock());
    }

    private static List<SpanBatch> exportFor(LoadProfile profile, long seed, int seconds, MutableClock clock) {
        var load = new LoadGenerator(profile, seed, clock);
        var reports = new ArrayList<SpanBatch>();
        for (var second = 0; second < seconds; second++) {
            clock.advance(Duration.ofSeconds(1));
            reports.addAll(load.export());
        }
        return reports;
    }

    private record Arrival(ApplicationName application, MessageSpan span) {
    }
}
