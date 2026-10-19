package app.dodb.smd.monitor.json;

import app.dodb.smd.api.event.delivery.EventDelivery;
import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.monitor.hub.FailureCause;
import app.dodb.smd.monitor.hub.HubConfig;
import app.dodb.smd.monitor.hub.InvocationFilter;
import app.dodb.smd.monitor.hub.InvocationId;
import app.dodb.smd.monitor.hub.SynchronousHub;
import app.dodb.smd.monitor.hub.TapeQuery;
import app.dodb.smd.monitor.hub.TraceCursor;
import app.dodb.smd.monitor.hub.TraceOrder;
import app.dodb.smd.monitor.hub.TraceQuery;
import app.dodb.smd.monitor.hub.TypeSelection;
import app.dodb.smd.monitor.hub.intake.SpanBatch;
import app.dodb.smd.monitor.hub.span.ApplicationName;
import app.dodb.smd.monitor.hub.span.InstanceName;
import app.dodb.smd.monitor.hub.span.MessageKind;
import app.dodb.smd.monitor.hub.span.MessageSpan;
import app.dodb.smd.monitor.hub.span.MessageType;
import app.dodb.smd.monitor.hub.span.Outcome;
import app.dodb.smd.monitor.hub.span.SessionId;
import app.dodb.smd.monitor.hub.span.SpanRef;
import app.dodb.smd.monitor.hub.span.Stage;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueryViewTest {

    private static final ApplicationName TICKETS = new ApplicationName("tickets");
    private static final String TRACE = "0af7651916cd43dd8448eb211c80319c";
    private static final MessageType OPEN = new MessageType(MessageKind.COMMAND, "app.dodb.tickets.OpenTicket");
    private static final MessageType OPENED = new MessageType(MessageKind.EVENT, "app.dodb.tickets.TicketOpened");
    private final SynchronousHub hub = new SynchronousHub(HubConfig.withDefaults().build());
    private final Instant start = Instant.now().minusSeconds(5).truncatedTo(ChronoUnit.SECONDS);
    private final MessageId command = MessageId.generate();
    private final MessageId event = MessageId.generate();

    @Test
    void tapePage_handledEvent_namesEachInvocationWithItsParent() {
        receiveHandledEvent();

        var page = json(TapePageView.from(hub.session(), hub.tape(new TapeQuery(InvocationFilter.EVERY_INVOCATION, Duration.ZERO, Optional.empty(), 2))));

        assertThat(page.propertyNames()).containsExactly("hubSession", "sequence", "invocations", "total", "matching", "slowestMillis", "next");
        var handling = page.get("invocations").get(1);
        assertThat(handling.get("invocation").propertyNames()).containsExactly("sequence", "application", "messageId", "hasParent", "kind", "type",
            "handling", "processingGroup", "instance", "startedAtMillis", "threadName", "outcome");
        assertThat(handling.get("invocation").get("hasParent").asBoolean()).isTrue();
        assertThat(handling.get("invocation").get("handling").asBoolean()).isTrue();
        assertThat(handling.get("invocation").get("processingGroup").isNull()).isTrue();
        assertThat(handling.get("parent").propertyNames()).containsExactly("sequence", "application", "messageId", "kind", "type", "handling",
            "processingGroup", "delivered", "retained");
        assertThat(handling.get("parent").get("messageId").asString()).isEqualTo(command.value().toString());
        assertThat(handling.get("parent").get("type").asString()).isEqualTo(OPEN.name());
        assertThat(handling.get("parent").get("handling").asBoolean()).isFalse();
        assertThat(handling.get("parent").get("retained").asBoolean()).isTrue();
        var entryPoint = page.get("invocations").get(0);
        assertThat(entryPoint.get("parent").isNull()).isTrue();
        assertThat(entryPoint.get("invocation").get("hasParent").asBoolean()).isFalse();
        assertThat(entryPoint.get("invocation").get("outcome").get("status").asString()).isEqualTo("FAILED");
        assertThat(page.get("next").asString()).isEqualTo("4");
        assertThat(TapePageView.parseCursor(page.get("next").asString())).isEqualTo(new InvocationId(4));
    }

    @Test
    void tapePage_parentNeverReported_hasAParentItCannotName() {
        hub.receive(new SpanBatch(TICKETS, new InstanceName("tickets-1"), new SessionId("session-1"), List.of(
            span("0000000000000002", Optional.of("0000000000000001"), MessageId.generate(), new Stage.Dispatch(OPEN), new Outcome.Completed(Duration.ZERO))), List.of()));

        var page = json(TapePageView.from(hub.session(), hub.tape(new TapeQuery(InvocationFilter.EVERY_INVOCATION, Duration.ZERO, Optional.empty(), 2))));

        var open = page.get("invocations").get(0);
        assertThat(open.get("invocation").get("hasParent").asBoolean()).isTrue();
        assertThat(open.get("parent").isNull()).isTrue();
    }

    @Test
    void tracePage_cursorOfThePage_isParsedBack() {
        receiveHandledEvent();
        hub.receive(new SpanBatch(TICKETS, new InstanceName("tickets-1"), new SessionId("session-1"), List.of(
            span("0000000000000009", Optional.empty(), MessageId.generate(), new Stage.Dispatch(OPEN), new Outcome.Completed(Duration.ZERO))), List.of()));
        var query = new TraceQuery(Optional.empty(), TypeSelection.EVERY_TYPE, Set.of(), Set.of(), false, "", TraceOrder.SLOWEST, Optional.empty(), 1);

        var page = json(TracePageView.from(hub.session(), hub.traces(query)));

        var trace = page.get("traces").get(0);
        assertThat(trace.propertyNames()).containsExactly("root", "startedAtMillis", "durationMillis", "messages", "handlings", "failures", "threads",
            "applications", "composition");
        assertThat(trace.get("composition").get("events").asInt()).isEqualTo(1);
        assertThat(trace.get("composition").get("handlings").asInt()).isEqualTo(2);
        assertThat(trace.get("messages").asInt()).isEqualTo(2);
        assertThat(TracePageView.parseCursor(page.get("next").asString())).isEqualTo(new TraceCursor(3_000_000, new InvocationId(6)));
    }

    @Test
    void parseCursor_notACursor_rejectsWithContext() {
        assertThatThrownBy(() -> TracePageView.parseCursor("newest"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("trace cursor", "'newest'");
        assertThatThrownBy(() -> TapePageView.parseCursor("0"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("tape cursor", "'0'");
    }

    @Test
    void failureCauses_failedCommandAskedFor_answersItsChainDownToTheHandlingThatRaised() {
        receiveHandledEvent();
        var asked = new FailureCause.Step(TICKETS, new Stage.Dispatch(OPEN), IllegalStateException.class.getName());

        var causes = json(FailureCausesView.from(hub.session(), hub.failures(List.of(asked)))).get("causes");

        assertThat(causes.size()).isEqualTo(1);
        var cause = causes.get(0);
        assertThat(cause.propertyNames()).containsExactly("cause", "retained", "latest", "chains");
        assertThat(cause.get("cause").get("kind").asString()).isEqualTo("COMMAND");
        assertThat(cause.get("retained").asInt()).isEqualTo(1);
        assertThat(cause.get("chains").get(0).get("steps").valueStream().map(step -> step.get("kind").asString() + " " + step.get("handling").asBoolean()))
            .containsExactly("COMMAND false", "COMMAND true", "EVENT false", "EVENT true");
        assertThat(cause.get("chains").get(0).get("steps").get(3).get("processingGroup").asString()).isEqualTo("ticket-view");
        assertThat(cause.get("latest").get(0).get("startedAtMillis").asDouble()).isEqualTo(start.toEpochMilli());
    }

    // A command handling publishing an event that a processing group failed to handle, which failed the publication, the command handling, and
    // the command.
    private void receiveHandledEvent() {
        var failed = new Outcome.Failed(Duration.ofMillis(3), IllegalStateException.class.getName());
        hub.receive(new SpanBatch(TICKETS, new InstanceName("tickets-1"), new SessionId("session-1"), List.of(
            span("0000000000000004", Optional.of("0000000000000003"), event, new Stage.Handling(OPENED, Optional.of("ticket-view")), failed),
            span("0000000000000003", Optional.of("0000000000000002"), event, new Stage.Dispatch(OPENED), failed),
            span("0000000000000002", Optional.of("0000000000000001"), command, new Stage.Handling(OPEN, Optional.empty()), failed),
            span("0000000000000001", Optional.empty(), command, new Stage.Dispatch(OPEN), failed)), List.of()));
    }

    // The span links to the span of the invocation it continues.
    private MessageSpan span(String spanId, Optional<String> continues, MessageId messageId, Stage stage, Outcome outcome) {
        var delivery = stage instanceof Stage.Handling handling && handling.processingGroup().isPresent() ? Optional.of(EventDelivery.SYNC) : Optional.<EventDelivery>empty();
        return new MessageSpan(SpanRef.of(TRACE, spanId), continues.map(continued -> SpanRef.of(TRACE, continued)), messageId, stage, delivery, start, "main", outcome);
    }

    private static JsonNode json(Object view) {
        return new ObjectMapper().valueToTree(view);
    }
}
