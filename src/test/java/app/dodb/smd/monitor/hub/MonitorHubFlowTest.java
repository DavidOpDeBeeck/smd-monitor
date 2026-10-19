package app.dodb.smd.monitor.hub;

import app.dodb.smd.api.event.delivery.EventDelivery;
import app.dodb.smd.monitor.hub.RecordingInstance.MutableClock;
import app.dodb.smd.monitor.hub.budget.Budgets;
import app.dodb.smd.monitor.hub.catalog.CausalLink;
import app.dodb.smd.monitor.hub.catalog.EventRoute;
import app.dodb.smd.monitor.hub.catalog.GroupLink;
import app.dodb.smd.monitor.hub.catalog.SinkRoute;
import app.dodb.smd.monitor.hub.span.ApplicationName;
import app.dodb.smd.monitor.hub.span.Outcome;
import app.dodb.smd.monitor.hub.span.SinkSend;
import app.dodb.smd.monitor.hub.span.SpanRef;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static app.dodb.smd.monitor.hub.RecordingInstance.ASSIGN;
import static app.dodb.smd.monitor.hub.RecordingInstance.AVAILABLE_AGENT;
import static app.dodb.smd.monitor.hub.RecordingInstance.BILLING;
import static app.dodb.smd.monitor.hub.RecordingInstance.OPEN;
import static app.dodb.smd.monitor.hub.RecordingInstance.OPENED;
import static app.dodb.smd.monitor.hub.RecordingInstance.TICKETS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class MonitorHubFlowTest {

    private final MutableClock clock = new MutableClock();
    private final SynchronousHub hub = new SynchronousHub(HubConfig.withDefaults().build(), clock);
    private final RecordingInstance tickets = new RecordingInstance(TICKETS, "tickets-1");
    private final RecordingInstance billing = new RecordingInstance(BILLING, "billing-1");

    @Test
    void statistics_dispatchesOfSeveralApplications_listsTheDispatchedTypesUnderEach() {
        var publication = tickets.dispatch(OPENED);
        tickets.reportTo(hub, publication, tickets.handle("ledger", publication));

        billing.reportTo(hub, billing.dispatch(ASSIGN));

        assertThat(hub.statistics().applications())
            .flatExtracting(application -> application.types().stream().map(type -> tuple(application.application(), type.type(), type.entryPoint())).toList())
            .containsExactly(tuple(BILLING, ASSIGN, true), tuple(TICKETS, OPENED, true));
    }

    @Test
    void statistics_childWithItsParentInTheSameApplication_linksThemWithinItWithoutACrossing() {
        var open = tickets.dispatch(OPEN);

        tickets.reportTo(hub, tickets.dispatch(ASSIGN, open), open);

        assertThat(application(TICKETS).links()).containsExactly(new LinkStatistics(new CausalLink(OPEN, ASSIGN), 1, 0));
        assertThat(application(TICKETS).crossings()).isEmpty();
    }

    @Test
    void statistics_parentReportedBeforeItsChild_countsTheLink() {
        var open = tickets.dispatch(OPEN);
        tickets.reportTo(hub, open);

        tickets.reportTo(hub, tickets.dispatch(ASSIGN, open));

        assertThat(application(TICKETS).links()).containsExactly(new LinkStatistics(new CausalLink(OPEN, ASSIGN), 1, 0));
    }

    @Test
    void statistics_parentReportedAfterItsChild_countsTheLink() {
        var open = tickets.dispatch(OPEN);
        tickets.reportTo(hub, tickets.dispatch(ASSIGN, open));

        tickets.reportTo(hub, open);

        assertThat(application(TICKETS).links()).containsExactly(new LinkStatistics(new CausalLink(OPEN, ASSIGN), 1, 0));
    }

    @Test
    void statistics_commandDispatchedByAnEventHandling_linksTheEventToTheCommand() {
        var publication = tickets.dispatch(OPENED);
        var handling = tickets.handle("assignment", publication);

        tickets.reportTo(hub, tickets.dispatch(ASSIGN, handling), handling, publication);

        assertThat(application(TICKETS).links()).containsExactly(new LinkStatistics(new CausalLink(OPENED, ASSIGN), 1, 0));
    }

    @Test
    void statistics_queryDispatchedByACommand_linksTheCommandToTheQuery() {
        var open = tickets.dispatch(OPEN);

        tickets.reportTo(hub, tickets.dispatch(AVAILABLE_AGENT, open), open);

        assertThat(application(TICKETS).links()).containsExactly(new LinkStatistics(new CausalLink(OPEN, AVAILABLE_AGENT), 1, 0));
    }

    @Test
    void statistics_eventPublishedByACommand_linksTheCommandToTheEvent() {
        var open = tickets.dispatch(OPEN);
        var publication = tickets.dispatch(OPENED, open);

        tickets.reportTo(hub, tickets.handle("assignment", publication), publication, open);

        assertThat(application(TICKETS).links()).containsExactly(new LinkStatistics(new CausalLink(OPEN, OPENED), 1, 0));
        assertThat(application(TICKETS).groupLinks()).isEmpty();
    }

    @Test
    void statistics_commandAndAHandledEventUnrelated_countNoLink() {
        var publication = tickets.dispatch(OPENED);

        tickets.reportTo(hub, tickets.dispatch(OPEN), publication, tickets.handle("assignment", publication));

        assertThat(application(TICKETS).links()).isEmpty();
    }

    @Test
    void statistics_eventDeliveredWithinTheApplication_countsNoLink() {
        var publication = tickets.dispatch(OPENED);

        tickets.reportTo(hub, tickets.republish(publication), publication);

        assertThat(application(TICKETS).links()).isEmpty();
    }

    @Test
    void statistics_childAndParentReportedAgain_countsTheLinkOnce() {
        var open = tickets.dispatch(OPEN);
        var assign = tickets.dispatch(ASSIGN, open);
        tickets.reportTo(hub, assign, open);

        tickets.reportTo(hub, open, assign);

        assertThat(application(TICKETS).links()).containsExactly(new LinkStatistics(new CausalLink(OPEN, ASSIGN), 1, 0));
    }

    @Test
    void statistics_childEvictedBeforeItsParentArrives_countsTheLink() {
        var hub = new SynchronousHub(HubConfig.withDefaults().build(), clock, Budgets.retaining(1));
        var open = tickets.dispatch(OPEN);
        tickets.reportTo(hub, tickets.dispatch(ASSIGN, open));

        tickets.reportTo(hub, open);

        assertThat(hub.statistics().application(TICKETS).orElseThrow().links()).containsExactly(new LinkStatistics(new CausalLink(OPEN, ASSIGN), 1, 0));
    }

    @Test
    void statistics_parentEvictedBeforeItsChildArrives_countsTheLink() {
        var hub = new SynchronousHub(HubConfig.withDefaults().build(), clock, Budgets.retaining(1));
        var open = tickets.dispatch(OPEN);
        tickets.reportTo(hub, open, tickets.dispatch(OPEN));

        tickets.reportTo(hub, tickets.dispatch(ASSIGN, open));

        assertThat(hub.statistics().application(TICKETS).orElseThrow().links()).containsExactly(new LinkStatistics(new CausalLink(OPEN, ASSIGN), 1, 0));
    }

    @Test
    void statistics_parentEvictedWithinTheLinkHorizon_countsTheLink() {
        var hub = new SynchronousHub(HubConfig.withDefaults().build(), clock, Budgets.retaining(1));
        var open = tickets.dispatch(OPEN);
        tickets.reportTo(hub, open, tickets.dispatch(OPEN));

        tickets.reportTo(hub, tickets.dispatch(ASSIGN, open));

        assertThat(hub.statistics().application(TICKETS).orElseThrow().links()).containsExactly(new LinkStatistics(new CausalLink(OPEN, ASSIGN), 1, 0));
    }

    @Test
    void statistics_parentEvictedAndReceivedBeforeTheLinkHorizon_countsNoLink() {
        var hub = new SynchronousHub(HubConfig.withDefaults().build(), clock, Budgets.retaining(1));
        var open = tickets.dispatch(OPEN);
        tickets.reportTo(hub, open, tickets.dispatch(OPEN));
        clock.advance(HubConfig.DEFAULT_LINK_HORIZON.plusSeconds(1));
        hub.epoch();

        tickets.reportTo(hub, RecordingInstance.with(tickets.dispatch(ASSIGN, open), clock.instant(), new Outcome.Completed(Duration.ofMillis(1))));

        assertThat(hub.statistics().application(TICKETS).orElseThrow().links()).isEmpty();
    }

    @Test
    void statistics_retainedParentReceivedBeforeTheLinkHorizon_countsTheLink() {
        var open = tickets.dispatch(OPEN);
        tickets.reportTo(hub, open);
        clock.advance(HubConfig.DEFAULT_LINK_HORIZON.plusSeconds(1));

        tickets.reportTo(hub, RecordingInstance.with(tickets.dispatch(ASSIGN, open), clock.instant(), new Outcome.Completed(Duration.ofMillis(1))));

        assertThat(application(TICKETS).links()).extracting(LinkStatistics::link).containsExactly(new CausalLink(OPEN, ASSIGN));
    }

    @Test
    void statistics_childOfAnEvictedPublicationInAnotherApplication_countsTheRouteAndTheDelay() {
        var hub = new SynchronousHub(HubConfig.withDefaults().build(), clock, Budgets.retaining(1));
        var publication = tickets.dispatch(OPENED);
        tickets.reportTo(hub, publication, tickets.dispatch(OPENED));
        var handling = RecordingInstance.with(billing.handle("invoicing", new EventDelivery("event-store"), publication), RecordingInstance.STARTED_AT.plusSeconds(1),
            new Outcome.Completed(Duration.ofMillis(1)));

        billing.reportTo(hub, handling);

        var billingStatistics = hub.statistics().application(BILLING).orElseThrow();
        assertThat(billingStatistics.routes()).extracting(EventRouteStatistics::route, EventRouteStatistics::history)
            .containsExactly(tuple(new EventRoute(TICKETS, OPENED, "invoicing"), 1L));
        assertThat(billingStatistics.groups().getFirst().handlings().getFirst().asynchronous()).isTrue();
    }

    @Test
    void statistics_childOfAMessageInAnotherApplication_isACrossingIntoTheChildApplication() {
        var open = tickets.dispatch(OPEN);
        tickets.reportTo(hub, open);

        billing.reportTo(hub, billing.dispatch(ASSIGN, open));

        assertThat(application(BILLING).crossings()).containsExactly(new CrossingStatistics(TICKETS, new LinkStatistics(new CausalLink(OPEN, ASSIGN), 1, 0)));
        assertThat(application(BILLING).links()).isEmpty();
        assertThat(application(TICKETS).crossings()).isEmpty();
    }

    @Test
    void statistics_eventDeliveredToAnotherApplication_isACrossingFromItsOrigin() {
        var publication = tickets.dispatch(OPENED);
        billing.reportTo(hub, billing.republish(publication));

        tickets.reportTo(hub, publication);

        assertThat(application(BILLING).crossings())
            .containsExactly(new CrossingStatistics(TICKETS, new LinkStatistics(new CausalLink(OPENED, OPENED), 1, 0)));
    }

    @Test
    void statistics_handlingInAnotherApplication_isNoCrossing() {
        var publication = tickets.dispatch(OPENED);
        tickets.reportTo(hub, publication);

        billing.reportTo(hub, billing.handle("ledger", publication));

        assertThat(hub.statistics().applications()).allSatisfy(application -> assertThat(application.crossings()).isEmpty());
    }

    @Test
    void statistics_groupHandlingAnEventOfItsApplication_routesTheEventToTheGroup() {
        var publication = tickets.dispatch(OPENED);

        tickets.reportTo(hub, publication, tickets.handle("ticket-view", publication));

        assertThat(application(TICKETS).routes()).containsExactly(new EventRouteStatistics(new EventRoute(TICKETS, OPENED, "ticket-view"), 1, 0));
    }

    @Test
    void statistics_groupHandlingAnEventOfAnotherApplication_routesItFromThePublishingApplication() {
        var publication = tickets.dispatch(OPENED);
        tickets.reportTo(hub, publication);

        billing.reportTo(hub, billing.handle("ledger", publication));

        assertThat(application(BILLING).routes()).containsExactly(new EventRouteStatistics(new EventRoute(TICKETS, OPENED, "ledger"), 1, 0));
        assertThat(application(TICKETS).routes()).isEmpty();
    }

    @Test
    void statistics_handlingReportedBeforeItsPublication_countsTheRoute() {
        var publication = tickets.dispatch(OPENED);
        billing.reportTo(hub, billing.handle("ledger", publication));

        tickets.reportTo(hub, publication);

        assertThat(application(BILLING).routes()).extracting(EventRouteStatistics::route).containsExactly(new EventRoute(TICKETS, OPENED, "ledger"));
    }

    @Test
    void statistics_commandDispatchedByAGroup_linksTheGroupToTheCommand() {
        var publication = tickets.dispatch(OPENED);
        var handling = tickets.handle("assignment", publication);

        tickets.reportTo(hub, tickets.dispatch(ASSIGN, handling), handling, publication);

        assertThat(application(TICKETS).groupLinks()).containsExactly(new GroupLinkStatistics(new GroupLink("assignment", ASSIGN), 1, 0));
    }

    @Test
    void statistics_commandDispatchedByAGroupHandlingAnotherApplicationsEvent_linksTheGroupWithinItsApplication() {
        var publication = tickets.dispatch(OPENED);
        tickets.reportTo(hub, publication);
        var handling = billing.handle("ledger", publication);

        billing.reportTo(hub, billing.dispatch(ASSIGN, handling), handling);

        assertThat(application(BILLING).groupLinks()).extracting(GroupLinkStatistics::link).containsExactly(new GroupLink("ledger", ASSIGN));
        assertThat(application(BILLING).routes()).extracting(EventRouteStatistics::route).containsExactly(new EventRoute(TICKETS, OPENED, "ledger"));
        assertThat(application(TICKETS).groupLinks()).isEmpty();
    }

    @Test
    void statistics_commandDispatchedByACommand_linksNoGroup() {
        var open = tickets.dispatch(OPEN);

        tickets.reportTo(hub, tickets.dispatch(ASSIGN, open), open);

        assertThat(application(TICKETS).groupLinks()).isEmpty();
        assertThat(application(TICKETS).routes()).isEmpty();
    }

    @Test
    void statistics_publicationSentToSinks_routesTheEventToTheDeliveryOfEachSink() {
        var sharedLog = new EventDelivery("shared-log");

        tickets.reportSendsTo(hub, tickets.send(OPENED, EventDelivery.SYNC), tickets.send(OPENED, sharedLog), tickets.send(OPENED, sharedLog));

        assertThat(application(TICKETS).sinkRoutes()).containsExactly(
            new SinkRouteStatistics(new SinkRoute(OPENED, EventDelivery.SYNC), 1, 0),
            new SinkRouteStatistics(new SinkRoute(OPENED, sharedLog), 2, 0));
    }

    @Test
    void statistics_sinkRouteOlderThanTheThroughputHistory_countsNothing() {
        tickets.reportSendsTo(hub, tickets.send(OPENED, new EventDelivery("shared-log")));

        clock.advance(HubConfig.DEFAULT_HISTORY.plusMinutes(1));
        tickets.reportTo(hub);

        assertThat(application(TICKETS).sinkRoutes()).extracting(SinkRouteStatistics::history).containsExactly(0L);
    }

    @Test
    void statistics_sinkSendReportedTwice_countsItOnce() {
        var send = tickets.send(OPENED, new EventDelivery("shared-log"));
        tickets.reportSendsTo(hub, send);

        tickets.reportSendsTo(hub, send);

        assertThat(application(TICKETS).sinkRoutes()).extracting(SinkRouteStatistics::history).containsExactly(1L);
    }

    @Test
    void statistics_sinkSends_retainNoInvocation() {
        tickets.reportSendsTo(hub, tickets.send(OPENED, new EventDelivery("shared-log")));

        assertThat(hub.health().application(TICKETS).orElseThrow().retained()).isZero();
        assertThat(application(TICKETS).types()).isEmpty();
    }

    @Test
    void new_sinkSendOfACommand_rejectsNamingTheTypeAndDelivery() {
        assertThatThrownBy(() -> new SinkSend(SpanRef.of("0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331"), OPEN, new EventDelivery("shared-log"),
            RecordingInstance.STARTED_AT))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("'shared-log'", "COMMAND", "OpenTicket");
    }

    @Test
    void new_sinkRouteOfACommand_rejectsNamingTheTypeAndDelivery() {
        assertThatThrownBy(() -> new SinkRoute(OPEN, new EventDelivery("shared-log")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("'shared-log'", "COMMAND", "OpenTicket");
    }

    @Test
    void new_routeOfACommand_rejectsNamingTheTypeAndGroup() {
        assertThatThrownBy(() -> new EventRoute(TICKETS, OPEN, "ticket-view"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("'ticket-view'", "COMMAND", "OpenTicket");
    }

    @Test
    void statistics_crossingOlderThanTheThroughputHistory_countsNothing() {
        var open = tickets.dispatch(OPEN);
        tickets.reportTo(hub, open);
        billing.reportTo(hub, billing.dispatch(ASSIGN, open));

        clock.advance(HubConfig.DEFAULT_HISTORY.plusMinutes(1));
        billing.reportTo(hub);

        assertThat(application(BILLING).crossings()).extracting(crossing -> crossing.link().history()).containsExactly(0L);
    }

    private ApplicationStatistics application(ApplicationName name) {
        return hub.statistics().application(name).orElseThrow();
    }
}
