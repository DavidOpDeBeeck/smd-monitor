package app.dodb.smd.monitor.hub;

import app.dodb.smd.monitor.hub.RecordingInstance.MutableClock;
import app.dodb.smd.monitor.hub.budget.Budgets;
import org.junit.jupiter.api.Test;

import java.util.List;

import static app.dodb.smd.monitor.hub.RecordingInstance.ASSIGN;
import static app.dodb.smd.monitor.hub.RecordingInstance.AVAILABLE_AGENT;
import static app.dodb.smd.monitor.hub.RecordingInstance.BILLING;
import static app.dodb.smd.monitor.hub.RecordingInstance.OPEN;
import static app.dodb.smd.monitor.hub.RecordingInstance.OPENED;
import static app.dodb.smd.monitor.hub.RecordingInstance.TICKETS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class MonitorHubLineageTest {

    private final MutableClock clock = new MutableClock();
    private final SynchronousHub hub = new SynchronousHub(HubConfig.withDefaults().build(), clock);
    private final RecordingInstance tickets = new RecordingInstance(TICKETS, "tickets-1");
    private final RecordingInstance otherTickets = new RecordingInstance(TICKETS, "tickets-2");
    private final RecordingInstance billing = new RecordingInstance(BILLING, "billing-1");

    @Test
    void receive_childBeforeItsParentInOneReport_findsTheParentBySpan() {
        var open = tickets.dispatch(OPEN);

        tickets.reportTo(hub, tickets.dispatch(ASSIGN, open), open);

        assertThat(parents()).extracting(ParentSummary::child, ParentSummary::application, ParentSummary::parent, ParentSummary::delivered)
            .containsExactly(tuple(id(1), TICKETS, id(2), false));
    }

    @Test
    void receive_parentAfterItsChild_findsTheParentWhenItArrives() {
        var open = tickets.dispatch(OPEN);
        otherTickets.reportTo(hub, otherTickets.dispatch(ASSIGN, open));

        tickets.reportTo(hub, open);

        var retained = hub.retained();
        assertThat(retained.invocations()).extracting(Invocation::type).containsExactly(ASSIGN, OPEN);
        assertThat(retained.parents()).extracting(ParentSummary::child, ParentSummary::parent).containsExactly(tuple(id(1), id(2)));
    }

    @Test
    void receive_handlingAndTheChildItDispatched_linksTheHandlingToThePublicationAndTheChildToTheHandling() {
        var publication = tickets.dispatch(OPENED);
        var handling = tickets.handle("assignments", publication);

        tickets.reportTo(hub, tickets.dispatch(ASSIGN, handling), handling, publication);

        assertThat(parents()).extracting(ParentSummary::child, ParentSummary::parent).containsExactly(tuple(id(1), id(2)), tuple(id(2), id(4)));
    }

    @Test
    void receive_queryDispatchedByACommand_nestsTheQueryUnderTheCommand() {
        var open = tickets.dispatch(OPEN);

        tickets.reportTo(hub, open, tickets.dispatch(AVAILABLE_AGENT, open));

        assertThat(parents()).extracting(ParentSummary::child, ParentSummary::parent, ParentSummary::delivered, ParentSummary::handling)
            .containsExactly(tuple(id(2), id(1), false, false));
    }

    @Test
    void receive_parentNeverReported_findsNoParent() {
        billing.reportTo(hub, billing.dispatchContinuingUnknown(ASSIGN));

        assertThat(parents()).isEmpty();
    }

    @Test
    void receive_eventDeliveredToAnotherApplication_isADeliveryOfItsOrigin() {
        var open = tickets.dispatch(OPEN);
        var publication = tickets.dispatch(OPENED, open);
        var delivery = billing.republish(publication);
        tickets.reportTo(hub, publication, open);

        billing.reportTo(hub, delivery, billing.handle("ledger", delivery));

        assertThat(parents()).extracting(ParentSummary::child, ParentSummary::application, ParentSummary::parent, ParentSummary::delivered)
            .containsExactly(tuple(id(1), TICKETS, id(2), false), tuple(id(4), TICKETS, id(1), true), tuple(id(6), BILLING, id(4), false));
    }

    @Test
    void receive_deliveryBeforeItsOrigin_findsTheOriginWhenItArrives() {
        var open = tickets.dispatch(OPEN);
        var publication = tickets.dispatch(OPENED, open);
        billing.reportTo(hub, billing.republish(publication));

        tickets.reportTo(hub, publication, open);

        assertThat(parents()).extracting(ParentSummary::child, ParentSummary::application, ParentSummary::delivered)
            .containsExactly(tuple(id(1), TICKETS, true), tuple(id(2), TICKETS, false));
    }

    @Test
    void receive_childOfAMessageInAnotherApplication_findsItsParentThereWithTheSequenceOfTheHub() {
        var open = tickets.dispatch(OPEN);
        tickets.reportTo(hub, open);

        billing.reportTo(hub, billing.dispatch(ASSIGN, open));

        var changes = hub.retained();
        assertThat(changes.invocations()).extracting(Invocation::id, Invocation::application).containsExactly(tuple(id(1), TICKETS), tuple(id(2), BILLING));
        assertThat(changes.parents())
            .extracting(ParentSummary::sequence, ParentSummary::child, ParentSummary::application, ParentSummary::parent, ParentSummary::delivered)
            .containsExactly(tuple(3L, id(2), TICKETS, id(1), false));
        assertThat(changes.sequence()).isEqualTo(3);
    }

    @Test
    void receive_childEvicted_forgetsItsParent() {
        var hub = new SynchronousHub(HubConfig.withDefaults().build(), clock, Budgets.retaining(2));
        var open = RecordingInstance.beforeTheWindow(tickets.dispatch(OPEN));
        tickets.reportTo(hub, RecordingInstance.beforeTheWindow(tickets.dispatch(ASSIGN, open)), open);

        tickets.reportTo(hub, tickets.dispatch(OPEN));

        assertThat(hub.retained().parents()).isEmpty();
    }

    @Test
    void receive_parentArrivingAfterItsChildWasEvicted_findsNoParent() {
        var hub = new SynchronousHub(HubConfig.withDefaults().build(), clock, Budgets.retaining(1));
        var open = tickets.dispatch(OPEN);
        tickets.reportTo(hub, RecordingInstance.beforeTheWindow(tickets.dispatch(ASSIGN, open)));
        tickets.reportTo(hub, tickets.dispatch(OPEN));

        tickets.reportTo(hub, open);

        assertThat(hub.retained().parents()).isEmpty();
    }

    private List<ParentSummary> parents() {
        return hub.retained().parents();
    }

    private static InvocationId id(long value) {
        return new InvocationId(value);
    }
}
