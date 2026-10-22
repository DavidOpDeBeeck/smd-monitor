package app.dodb.smd.monitor.json;

import app.dodb.smd.api.event.delivery.EventDelivery;
import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.monitor.hub.HubConfig;
import app.dodb.smd.monitor.hub.SynchronousHub;
import app.dodb.smd.monitor.hub.intake.SpanBatch;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class FlowViewTest {

    private static final ApplicationName TICKETS = new ApplicationName("tickets");
    private static final String TRACE = "0af7651916cd43dd8448eb211c80319c";
    private static final MessageType COMMAND = new MessageType(MessageKind.COMMAND, "app.dodb.tickets.OpenTicket");
    private static final MessageType EVENT = new MessageType(MessageKind.EVENT, "app.dodb.tickets.TicketOpened");
    private final SynchronousHub hub = new SynchronousHub(HubConfig.withDefaults().window(Duration.ofSeconds(30)).history(Duration.ofSeconds(90)).build());
    private final Instant start = Instant.now().minusSeconds(8).truncatedTo(ChronoUnit.SECONDS);

    @Test
    void from_applicationWithAHandledEvent_listsItsLinksAndRoutes() {
        var event = MessageId.generate();
        hub.receive(new SpanBatch(TICKETS, new InstanceName("tickets-1"), new SessionId("session-1"), List.of(
            span("0000000000000003", Optional.of("0000000000000002"), event, new Stage.Handling(EVENT, "ticket-view")),
            span("0000000000000002", Optional.of("0000000000000001"), event, new Stage.Dispatch(EVENT)),
            span("0000000000000001", Optional.empty(), MessageId.generate(), new Stage.Dispatch(COMMAND))), List.of()));

        var application = json().get("applications").get(0);

        assertThat(application.get("name").asString()).isEqualTo(TICKETS.value());
        assertThat(application.get("links").get(0).get("parent").get("name").asString()).isEqualTo(COMMAND.name());
        assertThat(application.get("links").get(0).get("history").asLong()).isEqualTo(1);
        var route = application.get("routes").get(0);
        assertThat(route.get("publisher").asString()).isEqualTo(TICKETS.value());
        assertThat(route.get("event").get("name").asString()).isEqualTo(EVENT.name());
        assertThat(route.get("processingGroup").asString()).isEqualTo("ticket-view");
        assertThat(route.get("history").asLong()).isEqualTo(1);
        assertThat(application.get("groupLinks").isEmpty()).isTrue();
    }

    @Test
    void from_publicationSentToSinks_listsEachSinkRouteWithItsDelivery() {
        hub.receive(new SpanBatch(TICKETS, new InstanceName("tickets-1"), new SessionId("session-1"), List.of(),
            List.of(new SinkSend(SpanRef.of(TRACE, "0000000000000005"), EVENT, new EventDelivery("shared-log"), start))));

        var route = json().get("applications").get(0).get("sinkRoutes").get(0);

        assertThat(route.propertyNames()).containsExactly("event", "delivery", "history", "lastSecond");
        assertThat(route.get("event").get("kind").asString()).isEqualTo("EVENT");
        assertThat(route.get("event").get("name").asString()).isEqualTo(EVENT.name());
        assertThat(route.get("delivery").asString()).isEqualTo("shared-log");
        assertThat(route.get("history").asLong()).isEqualTo(1);
    }

    @Test
    void from_anyFlow_hasTheFieldsOfTheWireContract() {
        hub.receive(new SpanBatch(TICKETS, new InstanceName("tickets-1"), new SessionId("session-1"),
            List.of(span("0000000000000001", Optional.empty(), MessageId.generate(), new Stage.Dispatch(COMMAND))), List.of()));

        var flow = json();

        assertThat(flow.propertyNames()).containsExactly("hubSession", "sequence", "applications");
        assertThat(flow.get("applications").get(0).propertyNames()).containsExactly("name", "links", "routes", "sinkRoutes", "groupLinks", "types",
            "failingTypes");
    }

    @Test
    void from_dispatchedType_listsWhatItsCardShows() {
        hub.receive(new SpanBatch(TICKETS, new InstanceName("tickets-1"), new SessionId("session-1"),
            List.of(span("0000000000000001", Optional.empty(), MessageId.generate(), new Stage.Dispatch(COMMAND))), List.of()));

        var type = json().get("applications").get(0).get("types").get(0);

        assertThat(type.propertyNames()).containsExactly("type", "windowStarted", "history");
        assertThat(type.get("type").get("name").asString()).isEqualTo(COMMAND.name());
        assertThat(type.get("windowStarted").asLong()).isEqualTo(1);
        assertThat(type.get("history").get(0).asLong()).isEqualTo(1);
    }

    // A flow card rings as its type fails, so the flow carries the failures of the types that failed in the window, and of those only.
    @Test
    void from_typeFailedInTheWindow_listsItsFailuresSecondBySecond() {
        hub.receive(new SpanBatch(TICKETS, new InstanceName("tickets-1"), new SessionId("session-1"), List.of(
            span("0000000000000001", Optional.empty(), MessageId.generate(), new Stage.Dispatch(COMMAND),
                new Outcome.Failed(Duration.ofMillis(1), IllegalStateException.class.getName())),
            span("0000000000000002", Optional.empty(), MessageId.generate(), new Stage.Dispatch(EVENT))), List.of()));

        var failing = json().get("applications").get(0).get("failingTypes");

        assertThat(failing.size()).isEqualTo(1);
        assertThat(failing.get(0).get("type").get("name").asString()).isEqualTo(COMMAND.name());
        assertThat(failing.get(0).get("failedPerSecond").valueStream().mapToLong(JsonNode::asLong).sum()).isEqualTo(1);
    }

    private JsonNode json() {
        var epoch = hub.epoch();
        return new ObjectMapper().valueToTree(FlowView.from(epoch.session(), epoch.sequence(), epoch.statistics().applications()));
    }

    // The span links to the span of the invocation it continues.
    private MessageSpan span(String spanId, Optional<String> continues, MessageId messageId, Stage stage) {
        return span(spanId, continues, messageId, stage, new Outcome.Completed(Duration.ZERO));
    }

    private MessageSpan span(String spanId, Optional<String> continues, MessageId messageId, Stage stage, Outcome outcome) {
        var delivery = stage instanceof Stage.Handling ? Optional.of(EventDelivery.SYNC) : Optional.<EventDelivery>empty();
        return new MessageSpan(SpanRef.of(TRACE, spanId), continues.map(continued -> SpanRef.of(TRACE, continued)), messageId, stage, delivery, start, "main",
            outcome);
    }
}
