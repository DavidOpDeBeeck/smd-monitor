package app.dodb.smd.monitor.hub.intake;

import app.dodb.smd.api.event.delivery.EventDelivery;
import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.monitor.hub.HubConfig;
import app.dodb.smd.monitor.hub.intake.Verdict.Admitted;
import app.dodb.smd.monitor.hub.intake.Verdict.Refused;
import app.dodb.smd.monitor.hub.span.ApplicationName;
import app.dodb.smd.monitor.hub.span.InstanceName;
import app.dodb.smd.monitor.hub.span.MessageKind;
import app.dodb.smd.monitor.hub.span.MessageSpan;
import app.dodb.smd.monitor.hub.span.MessageType;
import app.dodb.smd.monitor.hub.span.Outcome;
import app.dodb.smd.monitor.hub.span.SessionId;
import app.dodb.smd.monitor.hub.span.SinkSend;
import app.dodb.smd.monitor.hub.span.SpanRef;
import app.dodb.smd.monitor.hub.span.Stage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.type;

class AdmissionTest {

    private static final ApplicationName TICKETS = new ApplicationName("ticket-service");
    private static final ApplicationName BILLING = new ApplicationName("billing-service");
    private static final MessageType OPEN = new MessageType(MessageKind.COMMAND, "OpenTicket");
    private static final MessageType OPENED = new MessageType(MessageKind.EVENT, "TicketOpened");
    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");

    private final AtomicLong ids = new AtomicLong();
    private final Admission admission = new Admission(HubConfig.withDefaults().maxApplications(2).maxInstances(2).build());

    @Test
    void admit_batchWithinTheLimits_admitsItWhole() {
        var batch = batch(TICKETS, "tickets-1", span(NOW), span(NOW.plus(Admission.MAX_CLOCK_SKEW)));

        var verdict = admission.admit(batch, NOW);

        assertThat(verdict).isEqualTo(new Admitted(batch, Optional.empty()));
        assertThat(admission.refused()).isEqualTo(new RefusedSpans(0, Map.of(), Map.of()));
    }

    @Test
    void admit_newApplicationBeyondTheLimit_refusesEverySpanAndSendWithContext() {
        admission.admit(batch(TICKETS, "tickets-1", span(NOW)), NOW);
        admission.admit(batch(BILLING, "billing-1", span(NOW)), NOW);
        var oneTooMany = new SpanBatch(new ApplicationName("one-too-many"), new InstanceName("instance"), new SessionId("session"), List.of(span(NOW), span(NOW)),
            List.of(send(NOW)));

        var verdict = admission.admit(oneTooMany, NOW);

        assertThat(verdict).asInstanceOf(type(Refused.class)).satisfies(refused -> {
            assertThat(refused.refusal().spans()).isEqualTo(3);
            assertThat(refused.refusal().reason()).contains("already monitors 2 applications", "one-too-many");
        });
        assertThat(admission.refused()).isEqualTo(new RefusedSpans(3, Map.of(), Map.of()));
        assertThat(admission.instances(NOW)).containsOnlyKeys(TICKETS, BILLING);
    }

    @Test
    void admit_newInstanceBeyondTheLimitWhileNoneIsSilent_refusesItsSpansWithContext() {
        admission.admit(batch(TICKETS, "tickets-1", span(NOW)), NOW);
        admission.admit(batch(TICKETS, "tickets-2", span(NOW)), NOW);

        var verdict = admission.admit(batch(TICKETS, "one-too-many", span(NOW), span(NOW)), NOW.plusSeconds(9));

        assertThat(verdict).asInstanceOf(type(Refused.class)).satisfies(refused -> {
            assertThat(refused.refusal().spans()).isEqualTo(2);
            assertThat(refused.refusal().reason()).contains("ticket-service", "already has 2 reporting instances", "one-too-many");
        });
        assertThat(admission.refused()).isEqualTo(new RefusedSpans(0, Map.of(TICKETS, 2L), Map.of()));
    }

    @Test
    void admit_newInstanceBeyondTheLimit_retiresTheLongestSilentInstance() {
        admission.admit(batch(TICKETS, "tickets-1", span(NOW)), NOW);
        admission.admit(batch(TICKETS, "tickets-2", span(NOW)), NOW.plusSeconds(1));

        var verdict = admission.admit(batch(TICKETS, "tickets-3", span(NOW)), NOW.plusSeconds(11));

        assertThat(verdict).isInstanceOf(Admitted.class);
        assertThat(admission.instances(NOW.plusSeconds(11)).get(TICKETS)).extracting(instance -> instance.name().value()).containsExactly("tickets-2", "tickets-3");
    }

    @Test
    void admit_spansAndSendsStartedBeyondTheClockSkew_refusesExactlyThoseAndAdmitsTheRest() {
        var inTime = span(NOW.plus(Admission.MAX_CLOCK_SKEW));
        var ahead = span(NOW.plus(Admission.MAX_CLOCK_SKEW).plusMillis(1));
        var sendAhead = send(NOW.plus(Admission.MAX_CLOCK_SKEW).plusSeconds(1));
        var sendInTime = send(NOW);
        var batch = new SpanBatch(TICKETS, new InstanceName("tickets-1"), new SessionId("session"), List.of(ahead, inTime, ahead), List.of(sendAhead, sendInTime));

        var verdict = admission.admit(batch, NOW);

        assertThat(verdict).asInstanceOf(type(Admitted.class)).satisfies(admitted -> {
            assertThat(admitted.batch().spans()).containsExactly(inTime);
            assertThat(admitted.batch().sends()).containsExactly(sendInTime);
            assertThat(admitted.refusal()).hasValueSatisfying(refusal -> {
                assertThat(refusal.spans()).isEqualTo(3);
                assertThat(refusal.reason()).contains(ahead.span().spanId(), "tickets-1", "30 seconds", "clock");
            });
        });
        assertThat(admission.refused()).isEqualTo(new RefusedSpans(0, Map.of(), Map.of(TICKETS, 3L)));
    }

    @Test
    void admit_emptyBatchBeyondTheLimit_refusesNothingAndRegistersNothing() {
        admission.admit(batch(TICKETS, "tickets-1"), NOW);
        admission.admit(batch(BILLING, "billing-1"), NOW);
        var empty = batch(new ApplicationName("one-too-many"), "instance");

        var verdict = admission.admit(empty, NOW);

        assertThat(verdict).isEqualTo(new Admitted(empty, Optional.empty()));
        assertThat(admission.instances(NOW)).containsOnlyKeys(TICKETS, BILLING);
    }

    @Test
    void instances_reportedInstances_listTheirLatestSessionAndSilence() {
        admission.admit(batch(TICKETS, "tickets-1", span(NOW)), NOW);
        var restarted = new SpanBatch(TICKETS, new InstanceName("tickets-1"), new SessionId("restarted"), List.of(), List.of());
        admission.admit(restarted, NOW.plusSeconds(1));
        admission.admit(batch(TICKETS, "tickets-2", span(NOW)), NOW.plusSeconds(5));

        var instances = admission.instances(NOW.plusSeconds(11));

        assertThat(instances.get(TICKETS)).containsExactly(
            new ReportingInstance(new InstanceName("tickets-1"), new SessionId("restarted"), NOW.plusSeconds(1), true, false),
            new ReportingInstance(new InstanceName("tickets-2"), new SessionId("session"), NOW.plusSeconds(5), false, false));
    }

    @Test
    void instances_instanceRefusedAheadOfTheClock_isClockAheadForAMinute() {
        admission.admit(batch(TICKETS, "tickets-1", span(NOW.plus(Admission.MAX_CLOCK_SKEW).plusSeconds(1))), NOW);
        admission.admit(batch(TICKETS, "tickets-2", span(NOW)), NOW);

        var flagged = admission.instances(NOW.plusSeconds(59)).get(TICKETS);
        var cleared = admission.instances(NOW.plus(Admission.CLOCK_AHEAD_FLAGGED)).get(TICKETS);

        assertThat(flagged).extracting(ReportingInstance::clockAhead).containsExactly(true, false);
        assertThat(cleared).extracting(ReportingInstance::clockAhead).containsExactly(false, false);
    }

    @Test
    void retire_applicationWhoseInstancesAllSentNothingForRetireAfter_retiresItAndFreesItsPlace() {
        admission.admit(batch(TICKETS, "tickets-1", span(NOW)), NOW);
        admission.admit(batch(BILLING, "billing-1", span(NOW)), NOW.plusSeconds(1));
        var later = NOW.plus(HubConfig.DEFAULT_RETIRE_AFTER);

        var retired = admission.retire(later);

        assertThat(retired).containsExactly(TICKETS);
        assertThat(admission.instances(later)).containsOnlyKeys(BILLING);
        assertThat(admission.admit(batch(new ApplicationName("newcomer"), "newcomer-1", span(later)), later)).isInstanceOf(Admitted.class);
    }

    @Test
    void retire_oneOfTwoInstancesSilentForRetireAfter_retiresTheInstanceOnly() {
        admission.admit(batch(TICKETS, "tickets-1", span(NOW)), NOW);
        admission.admit(batch(TICKETS, "tickets-2", span(NOW)), NOW.plusSeconds(1));
        var later = NOW.plus(HubConfig.DEFAULT_RETIRE_AFTER);

        var retired = admission.retire(later);

        assertThat(retired).isEmpty();
        assertThat(admission.instances(later).get(TICKETS)).extracting(instance -> instance.name().value()).containsExactly("tickets-2");
    }

    private SpanBatch batch(ApplicationName application, String instance, MessageSpan... spans) {
        return new SpanBatch(application, new InstanceName(instance), new SessionId("session"), List.of(spans), List.of());
    }

    private MessageSpan span(Instant startedAt) {
        return new MessageSpan(spanRef(), Optional.empty(), MessageId.generate(), new Stage.Dispatch(OPEN), Optional.empty(), startedAt, "main",
            new Outcome.Completed(Duration.ofMillis(1)));
    }

    private SinkSend send(Instant startedAt) {
        return new SinkSend(spanRef(), OPENED, EventDelivery.SYNC, startedAt);
    }

    private SpanRef spanRef() {
        var id = ids.incrementAndGet();
        return SpanRef.of(HexFormat.of().toHexDigits(0L) + HexFormat.of().toHexDigits(id), HexFormat.of().toHexDigits(id));
    }
}
