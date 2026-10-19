package app.dodb.smd.monitor.hub.specimen;

import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.monitor.hub.InvocationId;
import app.dodb.smd.monitor.hub.ParentSummary;
import app.dodb.smd.monitor.hub.budget.Footprint;
import app.dodb.smd.monitor.hub.join.SpanKey;
import app.dodb.smd.monitor.hub.span.ApplicationName;
import app.dodb.smd.monitor.hub.span.InstanceName;
import app.dodb.smd.monitor.hub.span.MessageKind;
import app.dodb.smd.monitor.hub.span.MessageSpan;
import app.dodb.smd.monitor.hub.span.MessageType;
import app.dodb.smd.monitor.hub.span.Outcome;
import app.dodb.smd.monitor.hub.span.SpanRef;
import app.dodb.smd.monitor.hub.span.Stage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SpecimensTest {

    private static final ApplicationName TICKETS = new ApplicationName("ticket-service");
    private static final ApplicationName BILLING = new ApplicationName("billing-service");
    private static final InstanceName INSTANCE = new InstanceName("tickets-1");
    private static final MessageType OPEN = new MessageType(MessageKind.COMMAND, "OpenTicket");
    private static final MessageType ASSIGN = new MessageType(MessageKind.COMMAND, "AssignTicket");
    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");
    private static final long WINDOW_START = NOW.minusSeconds(60).getEpochSecond() * 1_000_000_000L;
    private static final String STORM = "java.lang.IllegalStateException";
    private static final String RARE = "java.lang.IllegalArgumentException";

    private final List<SpanKey> released = new ArrayList<>();
    private final Specimens specimens = new Specimens(released::add);
    private long ids;

    @Test
    void fit_traceRecentLetsGoThatNoReservoirKeeps_dropsItAndReleasesItsSpan() {
        var old = retain(OPEN, NOW.minusSeconds(120), Duration.ofMillis(1));
        tick();
        retain(OPEN, NOW, Duration.ofMillis(1));

        var fits = specimens.fit(singleTraces(1));

        assertThat(fits).isTrue();
        assertThat(specimens.retains(old.id())).isFalse();
        assertThat(released).containsExactly(SpanKey.of(old.span()));
        assertThat(specimens.retention(TICKETS)).extracting(Specimens.Retention::retained, Specimens.Retention::evicted).containsExactly(1, 1L);
        assertThat(specimens.retention(TICKETS).droppedThroughNanos()).isEqualTo(nanos(NOW.minusSeconds(120)));
    }

    @Test
    void fit_withinTheAllowance_dropsNothing() {
        retain(OPEN, NOW, Duration.ofMillis(1));
        retain(OPEN, NOW, Duration.ofMillis(1));

        var fits = specimens.fit(singleTraces(2));

        assertThat(fits).isTrue();
        assertThat(specimens.retention(TICKETS).retained()).isEqualTo(2);
        assertThat(specimens.bytes()).isEqualTo(singleTraces(2));
    }

    @Test
    void tick_specimenRetainedAfterAnEpoch_copiesOnlyTheChunkItChanged() {
        for (var index = 0; index < SpecimenChunk.SIZE + 1; index++) {
            retain(OPEN, NOW, Duration.ofMillis(1));
        }
        var before = tick();

        retain(OPEN, NOW, Duration.ofMillis(1));

        var after = tick();
        assertThat(after.chunks()).hasSize(2);
        assertThat(after.chunks()[0]).isSameAs(before.chunks()[0]);
        assertThat(after.chunks()[1]).isNotSameAs(before.chunks()[1]);
        assertThat(before.size()).isEqualTo(SpecimenChunk.SIZE + 1);
        assertThat(after.size()).isEqualTo(SpecimenChunk.SIZE + 2);
    }

    @Test
    void parentFound_parentRetained_mergesTheTracesUnderTheParentsRoot() {
        var parent = retain(OPEN, NOW, Duration.ofMillis(5));
        var child = retain(TICKETS, ASSIGN, NOW, Duration.ofMillis(1), Optional.of(parent.span()), null);

        specimens.parentFound(summary(child, parent));

        var view = tick();
        assertThat(view.traceOf(child.id().value())).hasValueSatisfying(trace -> {
            assertThat(trace.root()).isEqualTo(parent.id().value());
            assertThat(trace.size()).isEqualTo(2);
        });
        assertThat(view.specimen(child.id().value())).hasValueSatisfying(specimen -> assertThat(specimen.parent()).isPresent());
        assertThat(specimens.bytes()).isEqualTo(2 * Footprint.SPECIMEN + Footprint.PARENT + Footprint.TRACE);
    }

    @Test
    void parentFound_linksThatCloseACycle_keepOneTrace() {
        var first = retain(OPEN, NOW, Duration.ofMillis(1));
        var second = retain(TICKETS, ASSIGN, NOW, Duration.ofMillis(1), Optional.of(first.span()), null);
        specimens.parentFound(summary(second, first));

        specimens.parentFound(summary(first, second));

        var view = tick();
        assertThat(view.traceOf(first.id().value())).hasValueSatisfying(trace -> assertThat(trace.size()).isEqualTo(2));
    }

    @Test
    void parentFound_parentNotRetained_countsTheChildAFragment() {
        var child = retain(TICKETS, ASSIGN, NOW, Duration.ofMillis(1), Optional.of(span()), null);

        specimens.parentFound(new ParentSummary(++ids, child.id(), new InvocationId(++ids), TICKETS, MessageId.generate(), new Stage.Dispatch(OPEN), false));

        assertThat(specimens.retention(TICKETS).fragments()).isEqualTo(1);
    }

    @Test
    void fit_failureStormOfOneCause_keepsTheLatestOfThatCauseEveryOtherCauseAndTheSlowestHealthyTraces() {
        var rare = new ArrayList<Retained>();
        for (var index = 0; index < 3; index++) {
            rare.add(retain(TICKETS, ASSIGN, NOW, Duration.ofMillis(1), Optional.empty(), RARE));
        }
        // Ten healthy traces of the same type, slower than the rare failures, so only the Failures reservoir can keep those.
        var healthy = new ArrayList<Retained>();
        for (var seconds = 1; seconds <= 10; seconds++) {
            healthy.add(retain(ASSIGN, NOW, Duration.ofSeconds(seconds)));
        }
        for (var index = 0; index < 500; index++) {
            retain(TICKETS, OPEN, NOW, Duration.ofMillis(1), Optional.empty(), STORM);
        }

        specimens.fit(singleTraces(230));

        var view = tick();
        var storm = new int[1];
        view.forEach(specimen -> storm[0] += specimen.failureType().filter(STORM::equals).isPresent() ? 1 : 0);
        assertThat(rare).allSatisfy(kept -> assertThat(view.specimen(kept.id().value())).isPresent());
        assertThat(healthy.subList(5, 10)).allSatisfy(kept -> assertThat(view.specimen(kept.id().value())).isPresent());
        assertThat(healthy.subList(0, 5)).allSatisfy(dropped -> assertThat(view.specimen(dropped.id().value())).isEmpty());
        assertThat(storm[0]).isLessThanOrEqualTo(230);
        assertThat(specimens.bytes()).isLessThanOrEqualTo(singleTraces(230));
    }

    @Test
    void fit_oneApplicationFloods_dropsOnlyItsTracesWhileTheOtherIsWithinItsShare() {
        var billing = new ArrayList<Retained>();
        for (var index = 0; index < 3; index++) {
            billing.add(retain(BILLING, OPEN, NOW, Duration.ofMillis(1), Optional.empty(), null));
        }
        for (var index = 0; index < 100; index++) {
            retain(OPEN, NOW, Duration.ofMillis(1));
        }

        specimens.fit(singleTraces(20));

        assertThat(billing).allSatisfy(kept -> assertThat(specimens.retains(kept.id())).isTrue());
        assertThat(specimens.retention(BILLING).evicted()).isZero();
        assertThat(specimens.retention(TICKETS).retained()).isEqualTo(17);
    }

    @Test
    void fit_onlyReservoirsLeft_lowersSlowestKToZeroAndFailuresKToOneKeepingTheLatestOfEachCause() {
        for (var index = 0; index < 30; index++) {
            retain(TICKETS, OPEN, NOW, Duration.ofMillis(index + 1), Optional.empty(), STORM);
        }
        var latestRare = retain(TICKETS, ASSIGN, NOW, Duration.ofMillis(1), Optional.empty(), RARE);

        var fits = specimens.fit(0);

        assertThat(fits).isFalse();
        assertThat(specimens.retention(TICKETS)).extracting(Specimens.Retention::slowestK, Specimens.Retention::failuresK, Specimens.Retention::retained)
            .containsExactly(0, 1, 2);
        assertThat(specimens.retains(latestRare.id())).isTrue();
    }

    @Test
    void fit_oneApplicationsReservoirsShrink_keepsTheReservoirsOfAnApplicationWithinItsShare() {
        for (var index = 0; index < 6; index++) {
            retain(BILLING, OPEN, NOW, Duration.ofMillis(index + 1), Optional.empty(), null);
        }
        specimens.fit(singleTraces(5));
        for (var index = 0; index < 10; index++) {
            retain(TICKETS, OPEN, NOW, Duration.ofMillis(index + 1), Optional.empty(), STORM);
        }

        var fits = specimens.fit(singleTraces(12));

        assertThat(fits).isTrue();
        assertThat(specimens.retention(TICKETS)).extracting(Specimens.Retention::slowestK, Specimens.Retention::failuresK, Specimens.Retention::retained)
            .containsExactly(0, 7, 7);
        assertThat(specimens.retention(BILLING)).extracting(Specimens.Retention::slowestK, Specimens.Retention::retained, Specimens.Retention::evicted)
            .containsExactly(Reservoirs.SLOWEST, 5, 1L);
    }

    @Test
    void fit_traceAcrossApplicationsDropped_takesNoMoreFromTheOtherApplicationThanItsMembers() {
        var parent = retain(TICKETS, OPEN, NOW.minusSeconds(120), Duration.ofMillis(1), Optional.empty(), null);
        var child = retain(BILLING, ASSIGN, NOW.minusSeconds(120), Duration.ofMillis(1), Optional.of(parent.span()), null);
        specimens.parentFound(summary(child, parent));
        for (var index = 0; index < 4; index++) {
            retain(TICKETS, OPEN, NOW.minusSeconds(120), Duration.ofMillis(1), Optional.empty(), null);
        }
        for (var index = 0; index < 3; index++) {
            retain(BILLING, OPEN, NOW.minusSeconds(120), Duration.ofMillis(1), Optional.empty(), null);
        }
        tick();

        var fits = specimens.fit(singleTraces(6));

        assertThat(fits).isTrue();
        assertThat(specimens.retains(child.id())).isFalse();
        assertThat(specimens.retention(TICKETS)).extracting(Specimens.Retention::retained, Specimens.Retention::evicted).containsExactly(3, 2L);
        assertThat(specimens.retention(BILLING)).extracting(Specimens.Retention::retained, Specimens.Retention::evicted).containsExactly(3, 1L);
        assertThat(specimens.bytes()).isEqualTo(singleTraces(6));
    }

    @Test
    void retire_applicationInATraceAcrossApplications_leavesTheBytesOfWhatTheOtherRetainsAlone() {
        var parent = retain(BILLING, OPEN, NOW, Duration.ofMillis(1), Optional.empty(), null);
        var child = retain(TICKETS, ASSIGN, NOW, Duration.ofMillis(1), Optional.of(parent.span()), null);
        specimens.parentFound(summary(child, parent));
        retain(OPEN, NOW, Duration.ofMillis(1));

        specimens.retire(BILLING);

        assertThat(specimens.retains(child.id())).isFalse();
        assertThat(specimens.retention(TICKETS).bytes()).isEqualTo(Footprint.SPECIMEN);
        assertThat(specimens.bytes()).isEqualTo(singleTraces(1));
    }

    @Test
    void relax_wellWithinTheAllowance_raisesFailuresKBeforeSlowestK() {
        retain(TICKETS, OPEN, NOW, Duration.ofMillis(1), Optional.empty(), STORM);
        specimens.fit(0);

        specimens.relax(Long.MAX_VALUE);

        assertThat(specimens.retention(TICKETS)).extracting(Specimens.Retention::slowestK, Specimens.Retention::failuresK).containsExactly(0, 2);
    }

    private SpecimenView tick() {
        specimens.expire(WINDOW_START);
        return specimens.tick();
    }

    private static long singleTraces(int invocations) {
        return invocations * (Footprint.SPECIMEN + Footprint.TRACE);
    }

    private static long nanos(Instant instant) {
        return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
    }

    private Retained retain(MessageType type, Instant startedAt, Duration duration) {
        return retain(TICKETS, type, startedAt, duration, Optional.empty(), null);
    }

    private Retained retain(ApplicationName application, MessageType type, Instant startedAt, Duration duration, Optional<SpanRef> continues,
                            String failureType) {
        var id = new InvocationId(++ids);
        var outcome = failureType == null ? new Outcome.Completed(duration) : new Outcome.Failed(duration, failureType);
        var span = new MessageSpan(span(), continues, MessageId.generate(), new Stage.Dispatch(type), Optional.empty(), startedAt, "main", outcome);
        specimens.retain(id, application, INSTANCE, span);
        return new Retained(id, span.span());
    }

    private ParentSummary summary(Retained child, Retained parent) {
        return new ParentSummary(++ids, child.id(), parent.id(), TICKETS, MessageId.generate(), new Stage.Dispatch(OPEN), false);
    }

    private SpanRef span() {
        var id = ++ids;
        return SpanRef.of(HexFormat.of().toHexDigits(0L) + HexFormat.of().toHexDigits(id), HexFormat.of().toHexDigits(id));
    }

    private record Retained(InvocationId id, SpanRef span) {
    }
}
