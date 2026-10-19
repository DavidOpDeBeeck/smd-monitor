package app.dodb.smd.monitor.hub;

import app.dodb.smd.api.event.delivery.EventDelivery;
import app.dodb.smd.monitor.hub.RecordingInstance.MutableClock;
import app.dodb.smd.monitor.hub.budget.Budgets;
import app.dodb.smd.monitor.hub.budget.Footprint;
import app.dodb.smd.monitor.hub.catalog.CausalLink;
import app.dodb.smd.monitor.hub.health.ApplicationHealth;
import app.dodb.smd.monitor.hub.health.HealthReason;
import app.dodb.smd.monitor.hub.intake.Admission;
import app.dodb.smd.monitor.hub.span.ApplicationName;
import app.dodb.smd.monitor.hub.span.MessageKind;
import app.dodb.smd.monitor.hub.span.MessageType;
import app.dodb.smd.monitor.hub.span.Outcome;
import app.dodb.smd.monitor.hub.span.SinkSend;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static app.dodb.smd.monitor.hub.RecordingInstance.ASSIGN;
import static app.dodb.smd.monitor.hub.RecordingInstance.BILLING;
import static app.dodb.smd.monitor.hub.RecordingInstance.OPEN;
import static app.dodb.smd.monitor.hub.RecordingInstance.OPENED;
import static app.dodb.smd.monitor.hub.RecordingInstance.STARTED_AT;
import static app.dodb.smd.monitor.hub.RecordingInstance.TICKETS;
import static app.dodb.smd.monitor.hub.RecordingInstance.beforeTheWindow;
import static app.dodb.smd.monitor.hub.RecordingInstance.with;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class MonitorHubTest {

    private final MutableClock clock = new MutableClock();
    private final SynchronousHub hub = new SynchronousHub(HubConfig.withDefaults().build(), clock);
    private final RecordingInstance first = new RecordingInstance(TICKETS, "tickets-1");
    private final RecordingInstance second = new RecordingInstance(TICKETS, "tickets-2");

    @Test
    void receive_spanTheHubRetains_isNotRetainedOrCountedAgain() {
        var open = first.dispatch(OPEN);
        first.reportTo(hub, open, open);

        first.reportTo(hub, open);

        assertThat(changes().invocations()).hasSize(1);
        assertThat(type(OPEN).window().started()).isEqualTo(1);
    }

    @Test
    void statistics_twoInstances_sumsTheirCountsLatencyAndLinks() {
        var firstOpen = with(first.dispatch(OPEN), STARTED_AT, new Outcome.Completed(Duration.ofMillis(1)));
        var secondOpen = with(second.dispatch(OPEN), STARTED_AT, new Outcome.Completed(Duration.ofMillis(30)));
        first.reportTo(hub, first.dispatch(ASSIGN, firstOpen), firstOpen);
        second.reportTo(hub, second.dispatch(ASSIGN, secondOpen), secondOpen);

        var open = type(OPEN);

        assertThat(open.entryPoint()).isTrue();
        assertThat(open.window().started()).isEqualTo(2);
        assertThat(open.window().entered()).isEqualTo(2);
        assertThat(open.window().latency().total()).isEqualTo(Duration.ofMillis(31));
        assertThat(open.window().latency().max()).isEqualTo(Duration.ofMillis(30));
        assertThat(open.history()).isEqualTo(open.window());
        assertThat(type(ASSIGN).entryPoint()).isFalse();
        assertThat(statistics().links()).containsExactly(new LinkStatistics(new CausalLink(OPEN, ASSIGN), 2, 0));
    }

    @Test
    void statistics_dispatchesOfOneSecond_endTheirSeriesWithTheLastSettledSecond() {
        clock.advance(Duration.ofSeconds(1));

        first.reportTo(hub, first.dispatch(OPEN), first.dispatch(OPEN));

        var statistics = statistics();
        assertThat(hub.statistics().end()).isEqualTo(STARTED_AT.plusSeconds(3));
        assertThat(type(OPEN).startedPerSecond()).hasSize(60).endsWith(0L, 0L, 2L, 0L, 0L);
        assertThat(statistics.startedPerSecond().get(MessageKind.COMMAND)).hasSize(300).endsWith(2L, 0L, 0L);
        assertThat(statistics.enteredPerSecond()).hasSize(60).endsWith(2L, 0L, 0L);
    }

    @Test
    void statistics_dispatchWithoutALink_countsAnEntryPoint() {
        first.reportTo(hub, first.dispatch(OPEN));

        assertThat(type(OPEN).window()).extracting(Counts::started, Counts::entered).containsExactly(1L, 1L);
        assertThat(type(OPEN).entryPoint()).isTrue();
    }

    @Test
    void statistics_dispatchLinkingToASpanNeverReported_countsNeitherAnEntryPointNorALink() {
        var open = first.dispatch(OPEN);

        first.reportTo(hub, open, first.dispatchContinuingUnknown(ASSIGN));

        assertThat(type(ASSIGN).window()).extracting(Counts::started, Counts::entered).containsExactly(1L, 0L);
        assertThat(type(ASSIGN).entryPoint()).isFalse();
        assertThat(statistics().links()).isEmpty();
    }

    @Test
    void statistics_commandHandling_countsAHandlingOutsideTheProcessingGroups() {
        var open = first.dispatch(OPEN);

        first.reportTo(hub, open, first.handle(open));

        assertThat(statistics().groups()).isEmpty();
        assertThat(statistics().handlings().started()).isEqualTo(1);
        assertThat(statistics().dispatches()).extracting(Counts::started, Counts::entered).containsExactly(1L, 1L);
        assertThat(statistics().instances()).extracting(Instance::handlings).extracting(Counts::started).containsExactly(1L);
        assertThat(statistics().eventDelay()).isEqualTo(LatencyHistogram.EMPTY);
    }

    @Test
    void statistics_failedDispatch_countsItsFailure() {
        var failed = new Outcome.Failed(Duration.ofMillis(1), IllegalStateException.class.getName());

        first.reportTo(hub, with(first.dispatch(ASSIGN), STARTED_AT, failed));

        assertThat(type(ASSIGN).window()).extracting(Counts::failed, Counts::completed).containsExactly(1L, 1L);
        assertThat(type(ASSIGN).failedPerSecond()).endsWith(1L, 0L);
        assertThat(statistics().failedPerSecond()).endsWith(1L, 0L);
        assertThat(changes().invocations()).extracting(invocation -> invocation.span().outcome()).containsExactly(failed);
    }

    @Test
    void statistics_handlingsOfTwoGroups_listEachGroupWithItsEventTypes() {
        var publication = first.dispatch(OPENED);

        first.reportTo(hub, publication, first.handle("ticket-view", publication), first.handle("notifications", publication));

        assertThat(statistics().groups()).extracting(GroupStatistics::processingGroup, group -> group.window().started())
            .containsExactly(tuple("ticket-view", 1L), tuple("notifications", 1L));
        assertThat(statistics().groups().getFirst().handlings()).extracting(HandlingStatistics::type).containsExactly(OPENED);
        assertThat(statistics().handlings().started()).isEqualTo(2);
        assertThat(statistics().dispatches().started()).isEqualTo(1);
    }

    @Test
    void statistics_handlingsOfTwoGroups_nameTheDeliveryOfEachGroup() {
        var publication = first.dispatch(OPENED);

        first.reportTo(hub, publication, first.handle("ticket-view", EventDelivery.SYNC, publication),
            first.handle("ticket-activity", new EventDelivery("event-store"), publication));

        assertThat(statistics().groups()).extracting(GroupStatistics::processingGroup, GroupStatistics::delivery)
            .containsExactly(tuple("ticket-view", EventDelivery.SYNC), tuple("ticket-activity", new EventDelivery("event-store")));
    }

    @Test
    void statistics_groupHandledByInstancesDeliveringDifferently_namesTheDeliveryOfItsLatestHandling() {
        var publication = first.dispatch(OPENED);
        first.reportTo(hub, publication, first.handle("notifications", EventDelivery.SYNC, publication));

        second.reportTo(hub, second.handle("notifications", EventDelivery.ASYNC_AWAIT, publication));

        assertThat(statistics().groups()).singleElement().satisfies(group -> {
            assertThat(group.delivery()).isEqualTo(EventDelivery.ASYNC_AWAIT);
            assertThat(group.handlings()).extracting(HandlingStatistics::type).containsExactly(OPENED);
        });
    }

    @Test
    void statistics_typeIdleForLongerThanTheHistory_isListedWithoutCounts() {
        first.reportTo(hub, first.dispatch(OPEN));

        clock.advance(HubConfig.DEFAULT_HISTORY.plusMinutes(1));
        first.reportTo(hub);

        assertThat(type(OPEN).window()).isEqualTo(Counts.NONE);
        assertThat(type(OPEN).history()).isEqualTo(Counts.NONE);
    }

    @Test
    void statistics_secondsOlderThanTheThroughputHistory_areLeftOut() {
        var hub = new SynchronousHub(HubConfig.withDefaults().history(Duration.ofSeconds(90)).build(), clock);
        first.reportTo(hub, first.dispatch(OPEN));
        clock.advance(Duration.ofSeconds(88));
        var kept = hub.statistics();

        clock.advance(Duration.ofSeconds(1));

        var statistics = hub.statistics().application(TICKETS).orElseThrow();
        assertThat(kept.history()).isEqualTo(Duration.ofSeconds(90));
        assertThat(kept.application(TICKETS).orElseThrow().types().getFirst().history().started()).isEqualTo(1);
        assertThat(statistics.types().getFirst().history().started()).isZero();
        assertThat(hub.retained().invocations()).hasSize(1);
    }

    @Test
    void statistics_secondsOlderThanTheWindow_countInTheHistoryOnly() {
        var hub = new SynchronousHub(HubConfig.withDefaults().window(Duration.ofSeconds(10)).build(), clock);
        first.reportTo(hub, first.dispatch(OPEN));
        clock.advance(Duration.ofSeconds(9));

        var statistics = hub.statistics();

        var open = statistics.application(TICKETS).orElseThrow().types().getFirst();
        assertThat(statistics.window()).isEqualTo(Duration.ofSeconds(10));
        assertThat(open.window().started()).isZero();
        assertThat(open.history().started()).isEqualTo(1);
        assertThat(open.startedPerSecond()).hasSize(10);
    }

    @Test
    void retained_twoInstances_numbersInvocationsInReceiveOrderAndNamesTheirInstance() {
        first.reportTo(hub, first.dispatch(OPEN), first.dispatch(OPEN));
        second.reportTo(hub, second.dispatch(OPEN));

        var changes = changes();

        assertThat(changes.invocations()).extracting(invocation -> invocation.id().value(), Invocation::instance)
            .containsExactly(tuple(1L, first.name), tuple(2L, first.name), tuple(3L, second.name));
        assertThat(changes.sequence()).isEqualTo(3);
        assertThat(statistics().instances()).extracting(Instance::name).containsExactly(first.name, second.name);
    }

    @Test
    void receive_specimensBeyondTheirAllowance_dropsTheOldestTraceButKeepsCountingIt() {
        var hub = new SynchronousHub(HubConfig.withDefaults().build(), clock, Budgets.retaining(2));
        var dropped = beforeTheWindow(first.dispatch(OPEN));
        var kept = first.dispatch(OPEN);

        first.reportTo(hub, dropped, kept, first.dispatch(OPEN));

        var health = health(hub);
        assertThat(hub.retained().invocations()).extracting(invocation -> invocation.id().value()).containsExactly(2L, 3L);
        assertThat(health.count(HealthReason.EVICTED)).isEqualTo(1);
        assertThat(health.completeSince()).contains(dropped.startedAt());
        assertThat(health.oldestRetained()).contains(kept.startedAt());
        assertThat(hub.statistics().application(TICKETS).orElseThrow().types().getFirst().history().started()).isEqualTo(3);
    }

    @Test
    void health_retainedInvocations_accountsEachAsASpecimenAndItsTraceWithTheHub() {
        first.reportTo(hub, first.dispatch(OPEN));
        var one = retainedBytes(hub);

        first.reportTo(hub, first.dispatch(OPEN));

        assertThat(one).isEqualTo(Footprint.SPECIMEN);
        assertThat(hub.health().memory().specimens().bytes()).isEqualTo(2 * (Footprint.SPECIMEN + Footprint.TRACE));
        assertThat(retainedBytes(hub)).isEqualTo(2 * one);
        assertThat(health(hub).completeSince()).isEmpty();
    }

    @Test
    void receive_spanArrivingAgainAfterItsEviction_isNotCountedAgain() {
        var hub = new SynchronousHub(HubConfig.withDefaults().build(), clock, Budgets.retaining(1));
        var open = beforeTheWindow(first.dispatch(OPEN));
        first.reportTo(hub, open, first.dispatch(OPEN));

        first.reportTo(hub, open);

        assertThat(hub.statistics().application(TICKETS).orElseThrow().types().getFirst().history().started()).isEqualTo(2);
        assertThat(hub.retained().invocations()).extracting(invocation -> invocation.id().value()).containsExactly(2L);
    }

    @Test
    void health_droppedInvocation_accountsItsSpanWithTheJoinUntilItLeavesTheHorizon() {
        var hub = new SynchronousHub(HubConfig.withDefaults().build(), clock, Budgets.retaining(1));
        first.reportTo(hub, beforeTheWindow(first.dispatch(OPEN)));
        first.reportTo(hub, first.dispatch(ASSIGN));
        var joined = health(hub).join().bytes();

        clock.advance(HubConfig.DEFAULT_LINK_HORIZON.plusSeconds(1));

        assertThat(joined).isEqualTo(Footprint.FACT);
        assertThat(health(hub).join().bytes()).isZero();
        assertThat(health(hub)).extracting(ApplicationHealth::retained, health -> health.specimens().bytes()).containsExactly(1, Footprint.SPECIMEN);
        assertThat(health(hub).effectiveHorizon()).isEqualTo(HubConfig.DEFAULT_LINK_HORIZON);
    }

    @Test
    void retained_capacityReached_releasesTheHeapOfEvictedInvocationsAndTheirParents() {
        var hub = new SynchronousHub(HubConfig.withDefaults().build(), clock, Budgets.retaining(2));
        var fresh = new SynchronousHub(HubConfig.withDefaults().build(), clock, Budgets.retaining(2));
        var open = beforeTheWindow(first.dispatch(OPEN));
        first.reportTo(hub, beforeTheWindow(first.dispatch(ASSIGN, open)), open);
        first.reportTo(fresh, first.dispatch(OPEN), first.dispatch(OPEN));

        first.reportTo(hub, first.dispatch(OPEN), first.dispatch(OPEN));

        assertThat(retainedBytes(hub)).isEqualTo(retainedBytes(fresh));
    }

    @Test
    void statistics_noReports_listsNoApplicationAndRetainsNothing() {
        var statistics = hub.statistics();

        assertThat(statistics.applications()).isEmpty();
        assertThat(statistics.application(TICKETS)).isEmpty();
        assertThat(hub.retained()).satisfies(changes -> {
            assertThat(changes.sequence()).isZero();
            assertThat(changes.invocations()).isEmpty();
            assertThat(changes.parents()).isEmpty();
        });
    }

    @Test
    void statistics_reportsOfSeveralApplications_listsEachByNameWithItsOwnCounts() {
        var billing = new RecordingInstance(BILLING, "billing-1");
        var open = first.dispatch(OPEN);
        first.reportTo(hub, first.dispatch(ASSIGN, open), open);
        second.reportTo(hub, second.dispatch(OPEN));
        billing.reportTo(hub, billing.dispatch(OPEN));

        var applications = hub.statistics().applications();

        assertThat(applications).extracting(ApplicationStatistics::application).containsExactly(BILLING, TICKETS);
        assertThat(applications.getFirst().dispatches().started()).isEqualTo(1);
        assertThat(applications.getLast()).satisfies(tickets -> {
            assertThat(tickets.instances()).extracting(Instance::name).containsExactly(first.name, second.name);
            assertThat(tickets.dispatches().started()).isEqualTo(3);
            assertThat(tickets.dispatches().entered()).isEqualTo(2);
            assertThat(hub.health().application(TICKETS).orElseThrow().specimens().bytes())
                .isGreaterThan(hub.health().application(BILLING).orElseThrow().specimens().bytes());
        });
    }

    @Test
    void receive_spansOfTwoApplications_numbersThemWithOneSequenceAcrossTheHub() {
        var billing = new RecordingInstance(BILLING, "billing-1");

        first.reportTo(hub, first.dispatch(OPEN));
        billing.reportTo(hub, billing.dispatch(ASSIGN));
        first.reportTo(hub, first.dispatch(OPEN));

        var changes = changes();
        assertThat(changes.invocations()).extracting(invocation -> invocation.id().value(), Invocation::application)
            .containsExactly(tuple(1L, TICKETS), tuple(2L, BILLING), tuple(3L, TICKETS));
        assertThat(changes.sequence()).isEqualTo(3);
    }

    @Test
    void receive_applicationUsingTheMostOfTheAllowance_dropsOnlyItsOwnOldest() {
        var hub = new SynchronousHub(HubConfig.withDefaults().build(), clock, Budgets.retaining(3));
        var billing = new RecordingInstance(BILLING, "billing-1");
        var dropped = beforeTheWindow(first.dispatch(OPEN));
        first.reportTo(hub, dropped);
        billing.reportTo(hub, billing.dispatch(ASSIGN));

        first.reportTo(hub, first.dispatch(OPEN), first.dispatch(OPEN));

        var health = hub.health();
        assertThat(hub.retained().invocations()).extracting(invocation -> invocation.id().value(), Invocation::application)
            .containsExactly(tuple(2L, BILLING), tuple(3L, TICKETS), tuple(4L, TICKETS));
        assertThat(health.application(TICKETS).orElseThrow()).extracting(tickets -> tickets.count(HealthReason.EVICTED), ApplicationHealth::completeSince)
            .containsExactly(1L, Optional.of(dropped.startedAt()));
        assertThat(health.application(BILLING).orElseThrow()).extracting(billings -> billings.count(HealthReason.EVICTED), ApplicationHealth::completeSince)
            .containsExactly(0L, Optional.empty());
    }

    @Test
    void health_applicationRetainingNothing_takesNoSpecimenBytesAndHasNoOldestRetained() {
        var billing = new RecordingInstance(BILLING, "billing-1");
        first.reportTo(hub, first.dispatch(OPEN), first.dispatch(OPEN));

        billing.reportTo(hub);

        assertThat(hub.health().application(BILLING).orElseThrow())
            .extracting(ApplicationHealth::retained, health -> health.specimens().bytes(), ApplicationHealth::oldestRetained)
            .containsExactly(0, 0L, Optional.empty());
    }

    @Test
    void statistics_handlingStartedAfterItsPublicationReturned_countsItsEventDelayAndStaysAsynchronous() {
        var publication = with(first.dispatch(OPENED), STARTED_AT, new Outcome.Completed(Duration.ofMillis(1)));
        var handling = with(first.handle("notifications", publication), STARTED_AT.plusMillis(5), new Outcome.Completed(Duration.ofMillis(1)));
        first.reportTo(hub, handling, publication);

        var notifications = statistics().groups().getFirst().handlings().getFirst();

        assertThat(notifications.asynchronous()).isTrue();
        assertThat(notifications.delay().count()).isEqualTo(1);
        assertThat(notifications.delay().max()).isEqualTo(Duration.ofMillis(4));
        assertThat(statistics().eventDelay().count()).isEqualTo(1);
    }

    @Test
    void statistics_handlingWithinItsPublication_hasNoEventDelay() {
        var publication = with(first.dispatch(OPENED), STARTED_AT, new Outcome.Completed(Duration.ofMillis(5)));
        first.reportTo(hub, publication, with(first.handle("ticket-view", publication), STARTED_AT.plusMillis(1), new Outcome.Completed(Duration.ofMillis(1))));

        var view = statistics().groups().getFirst().handlings().getFirst();

        assertThat(view.asynchronous()).isFalse();
        assertThat(view.delay().count()).isZero();
        assertThat(statistics().eventDelay().count()).isZero();
    }

    @Test
    void statistics_failedTraceRecentLetGo_staysInTheFailuresReservoirAndCountsAmongTheRetainedPerType() {
        var hub = new SynchronousHub(HubConfig.withDefaults().build(), clock, Budgets.retaining(3));
        var failed = new Outcome.Failed(Duration.ofMillis(1), "java.lang.IllegalStateException");

        first.reportTo(hub, with(first.dispatch(OPEN), STARTED_AT, failed), beforeTheWindow(first.dispatch(OPEN)), first.dispatch(OPEN),
            with(first.dispatch(ASSIGN), STARTED_AT.plusSeconds(1), failed));

        var statistics = hub.statistics().application(TICKETS).orElseThrow();
        assertThat(statistics.types()).extracting(TypeStatistics::type, TypeStatistics::retained).containsExactly(tuple(OPEN, 2), tuple(ASSIGN, 1));
        assertThat(health(hub)).extracting(ApplicationHealth::retained, health -> health.count(HealthReason.EVICTED)).containsExactly(3, 1L);
        assertThat(health(hub).oldestRetained()).contains(STARTED_AT);
        assertThat(statistics.failedReceived()).isEqualTo(2);
    }

    @Test
    void health_parentFound_accountsTheChildsSummaryOfItWithTheSpecimens() {
        var open = first.dispatch(OPEN);

        first.reportTo(hub, open, first.dispatch(ASSIGN, open));

        assertThat(hub.retained().parents()).singleElement().satisfies(parent -> assertThat(parent.application()).isEqualTo(TICKETS));
        assertThat(retainedBytes(hub)).isEqualTo(2 * Footprint.SPECIMEN + Footprint.PARENT);
    }

    @Test
    void statistics_spanAndSendReceivedAgain_countsEachAsADuplicate() {
        var open = first.dispatch(OPEN);
        var send = first.send(OPENED, EventDelivery.SYNC);
        first.reportTo(hub, open);
        first.reportSendsTo(hub, send);

        first.reportTo(hub, open);
        first.reportSendsTo(hub, send);

        assertThat(health(hub).count(HealthReason.DUPLICATE)).isEqualTo(2);
        assertThat(statistics().sinkRoutes()).extracting(SinkRouteStatistics::history).containsExactly(1L);
    }

    @Test
    void statistics_evictedChildWhoseParentNeverArrives_isReportedUnlinkedOnceItsParentCouldNoLongerArriveInTime() {
        var hub = new SynchronousHub(HubConfig.withDefaults().build(), clock, Budgets.retaining(1));
        first.reportTo(hub, first.dispatchContinuingUnknown(ASSIGN), first.dispatch(OPEN));
        clock.advance(HubConfig.DEFAULT_LINK_HORIZON.plusSeconds(1));
        var leftTheHorizon = health(hub).count(HealthReason.PARENT_NEVER_RECEIVED);

        clock.advance(HubConfig.DEFAULT_LINK_HORIZON);

        assertThat(leftTheHorizon).isZero();
        assertThat(health(hub)).extracting(health -> health.count(HealthReason.PARENT_NEVER_RECEIVED), health -> health.count(HealthReason.PARENT_AFTER_HORIZON))
            .containsExactly(1L, 0L);
    }

    @Test
    void statistics_parentArrivingAfterItsEvictedChildLeftTheHorizon_reportsTheChildUnlinkedAfterTheHorizon() {
        var hub = new SynchronousHub(HubConfig.withDefaults().build(), clock, Budgets.retaining(1));
        var open = first.dispatch(OPEN);
        first.reportTo(hub, first.dispatch(ASSIGN, open), first.dispatch(OPEN));
        clock.advance(HubConfig.DEFAULT_LINK_HORIZON.plusSeconds(1));
        hub.statistics();

        first.reportTo(hub, open);

        assertThat(health(hub)).extracting(health -> health.count(HealthReason.PARENT_NEVER_RECEIVED), health -> health.count(HealthReason.PARENT_AFTER_HORIZON))
            .containsExactly(0L, 1L);
        assertThat(hub.statistics().application(TICKETS).orElseThrow().links()).isEmpty();
    }

    @Test
    void receive_spanStartedBeyondTheClockSkewAllowed_refusesItWithContextAndReceivesTheOthers() {
        var ahead = with(first.dispatch(OPEN), clock.instant().plus(Admission.MAX_CLOCK_SKEW).plusSeconds(1), new Outcome.Completed(Duration.ofMillis(1)));
        var inTime = first.dispatch(OPEN);

        var refusal = first.reportTo(hub, inTime, ahead, ahead);

        assertThat(refusal).hasValueSatisfying(refused -> {
            assertThat(refused.spans()).isEqualTo(2);
            assertThat(refused.reason()).contains(ahead.span().spanId(), "tickets-1", "30 seconds", "clock");
        });
        assertThat(changes().invocations()).extracting(Invocation::span).containsExactly(inTime);
    }

    @Test
    void receive_spanStartedWithinTheClockSkewAllowed_isRetained() {
        var ahead = with(first.dispatch(OPEN), clock.instant().plus(Admission.MAX_CLOCK_SKEW), new Outcome.Completed(Duration.ofMillis(1)));

        var refusal = first.reportTo(hub, ahead);

        assertThat(refusal).isEmpty();
        assertThat(changes().invocations()).hasSize(1);
    }

    @Test
    void receive_sinkSendStartedBeyondTheClockSkewAllowed_isRejectedWithContext() {
        var send = first.send(OPENED, EventDelivery.SYNC);
        var ahead = new SinkSend(send.span(), send.event(), send.delivery(), clock.instant().plus(Admission.MAX_CLOCK_SKEW).plusSeconds(1));

        var refusal = first.reportSendsTo(hub, ahead, send);

        assertThat(refusal).hasValueSatisfying(refused -> {
            assertThat(refused.spans()).isEqualTo(1);
            assertThat(refused.reason()).contains(ahead.span().spanId(), "clock");
        });
        assertThat(statistics().sinkRoutes()).extracting(SinkRouteStatistics::history).containsExactly(1L);
    }

    @Test
    void receive_newApplicationBeyondTheLimit_isRejectedWithContext() {
        var hub = new SynchronousHub(HubConfig.withDefaults().maxApplications(2).build(), clock);
        new RecordingInstance(new ApplicationName("application-1"), "instance").reportTo(hub);
        new RecordingInstance(new ApplicationName("application-2"), "instance").reportTo(hub);
        var oneTooMany = new RecordingInstance(new ApplicationName("one-too-many"), "instance");

        var refusal = oneTooMany.reportTo(hub, oneTooMany.dispatch(OPEN), oneTooMany.dispatch(OPEN));

        assertThat(refusal).hasValueSatisfying(refused -> {
            assertThat(refused.spans()).isEqualTo(2);
            assertThat(refused.reason()).contains("already monitors 2 applications", "one-too-many");
        });
        assertThat(hub.statistics().applications()).hasSize(2);
    }

    @Test
    void statistics_typeBeyondMaxTypes_countsUnderOtherAndInTheApplicationTotals() {
        var hub = new SynchronousHub(HubConfig.withDefaults().maxTypes(1).build(), clock);

        first.reportTo(hub, first.dispatch(OPEN), first.dispatch(ASSIGN), first.dispatch(ASSIGN));

        var statistics = hub.statistics().application(TICKETS).orElseThrow();
        assertThat(statistics.types()).extracting(TypeStatistics::type).containsExactly(OPEN);
        assertThat(statistics.other().dispatchWindow().started()).isEqualTo(2);
        assertThat(statistics.dispatches().started()).isEqualTo(3);
        assertThat(statistics.kinds().get(MessageKind.COMMAND).started()).isEqualTo(3);
    }

    @Test
    void statistics_spanArrivingAfterItsSecondSettled_countsItAsARevision() {
        first.reportTo(hub, first.dispatch(OPEN));

        var statistics = statistics();

        assertThat(health(hub)).extracting(health -> health.count(HealthReason.REVISED), health -> health.count(HealthReason.BEYOND_HISTORY)).containsExactly(1L, 0L);
        assertThat(statistics.provisionalFrom()).isEqualTo(clock.instant().minusSeconds(7));
    }

    private Retained changes() {
        return hub.retained();
    }

    private ApplicationStatistics statistics() {
        return hub.statistics().application(TICKETS).orElseThrow();
    }

    private static long retainedBytes(SynchronousHub hub) {
        return health(hub).specimens().bytes();
    }

    private static ApplicationHealth health(SynchronousHub hub) {
        return hub.health().application(TICKETS).orElseThrow();
    }

    private TypeStatistics type(MessageType type) {
        return statistics().types().stream().filter(statistics -> statistics.type().equals(type)).findFirst().orElseThrow();
    }
}
