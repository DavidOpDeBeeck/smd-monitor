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
import app.dodb.smd.monitor.hub.span.SpanRef;
import app.dodb.smd.monitor.hub.span.Stage;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class FrameViewTest {

    private static final ApplicationName TICKETS = new ApplicationName("tickets");
    private static final ApplicationName BILLING = new ApplicationName("billing");
    private static final String TRACE = "0af7651916cd43dd8448eb211c80319c";
    private static final MessageType COMMAND = new MessageType(MessageKind.COMMAND, "app.dodb.tickets.OpenTicket");
    private static final MessageType EVENT = new MessageType(MessageKind.EVENT, "app.dodb.tickets.TicketOpened");
    private static final MessageType INVOICE = new MessageType(MessageKind.COMMAND, "app.dodb.billing.CreateInvoice");
    private static final long HEAP = 512L * 1024 * 1024;
    private final SynchronousHub hub = new SynchronousHub(HubConfig.withDefaults().window(Duration.ofSeconds(30)).history(Duration.ofSeconds(90)).build());
    // Old enough that its second has settled into the window.
    private final Instant start = Instant.now().minusSeconds(8).truncatedTo(ChronoUnit.SECONDS);

    @Test
    void from_applicationWithAHandledEvent_listsItsStatisticsAndTheSequenceOfWhatTheHubRetains() {
        var command = MessageId.generate();
        var event = MessageId.generate();
        var failed = new Outcome.Failed(Duration.ofMillis(3), IllegalStateException.class.getName());
        hub.receive(new SpanBatch(TICKETS, new InstanceName("tickets-1"), new SessionId("session-1"), List.of(
            span("0000000000000003", Optional.of("0000000000000002"), event, new Stage.Handling(EVENT, "ticket-view"), failed),
            span("0000000000000002", Optional.of("0000000000000001"), event, new Stage.Dispatch(EVENT), failed),
            span("0000000000000001", Optional.empty(), command, new Stage.Dispatch(COMMAND), failed)), List.of()));

        var frame = json(OptionalLong.of(HEAP));

        assertThat(frame.get("sequence").asLong()).isEqualTo(5);
        assertThat(frame.get("windowSeconds").asLong()).isEqualTo(30);
        assertThat(frame.get("historySeconds").asLong()).isEqualTo(90);
        assertThat(frame.get("settleSeconds").asLong()).isEqualTo(5);
        assertThat(frame.get("retireAfterSeconds").asLong()).isEqualTo(HubConfig.DEFAULT_RETIRE_AFTER.toSeconds());
        assertThat(frame.get("maxHeapBytes").asLong()).isEqualTo(HEAP);
        var application = frame.get("applications").get(0);
        assertThat(application.get("name").asString()).isEqualTo("tickets");
        assertThat(application.get("failedReceived").asLong()).isEqualTo(3);
        assertThat(application.get("eventDelayP95Millis").isNull()).isTrue();
        var health = frame.get("health");
        assertThat(health.get("budgetBytes").asLong()).isEqualTo(HubConfig.DEFAULT_MEMORY);
        assertThat(health.get("effectiveHistorySeconds").asLong()).isEqualTo(90);
        assertThat(health.get("specimens").get("bytes").asLong()).isEqualTo(hub.health().memory().specimens().bytes()).isPositive();
        var tickets = health.get("applications").get(0);
        assertThat(tickets.get("name").asString()).isEqualTo("tickets");
        assertThat(tickets.get("retained").asInt()).isEqualTo(3);
        assertThat(tickets.get("oldestRetainedMillis").asDouble()).isEqualTo(start.toEpochMilli());
        assertThat(tickets.get("completeSinceMillis").isNull()).isTrue();
        assertThat(tickets.get("shedding").asString()).isEqualTo("NONE");
        assertThat(tickets.get("reasons").get("EVICTED").asLong()).isZero();
        assertThat(tickets.get("specimens").get("bytes").asLong()).isEqualTo(hub.health().application(TICKETS).orElseThrow().specimens().bytes()).isPositive();
        assertThat(application.get("instances").get(0).get("sessionId").asString()).isEqualTo("session-1");
        assertThat(application.get("instances").get(0).get("dispatches").get(0).asLong()).isEqualTo(2);
        var types = application.get("types");
        assertThat(types.valueStream().map(type -> type.get("kind").asString())).containsExactly("EVENT", "COMMAND");
        assertThat(types.get(1).get("entryPoint").asBoolean()).isTrue();
        assertThat(types.get(1).get("window").get(2).asLong()).isEqualTo(1);
        assertThat(types.get(1).get("window").get(9).asDouble()).isEqualTo(3.0);
        assertThat(types.get(1).get("retained").asInt()).isEqualTo(1);
        assertThat(types.get(1).get("startedPerSecond").size()).isEqualTo(30);
        var group = application.get("groups").get(0);
        assertThat(group.get("processingGroup").asString()).isEqualTo("ticket-view");
        assertThat(group.get("delivery").asString()).isEqualTo("sync");
        assertThat(group.get("handlings").get(0).get("name").asString()).isEqualTo(EVENT.name());
        assertThat(group.get("handlings").get(0).get("asynchronous").asBoolean()).isFalse();
        assertThat(group.get("handlings").get(0).get("delayP95Millis").isNull()).isTrue();
        assertThat(group.get("handlings").get(0).propertyNames()).containsExactly("name", "asynchronous", "delayP95Millis");
        assertThat(application.get("crossings").isEmpty()).isTrue();
        assertThat(application.get("kinds").valueStream().map(kind -> kind.get("kind").asString())).containsExactly("COMMAND", "QUERY", "EVENT");
        assertThat(application.get("kinds").get(0).get("startedPerSecond").size()).isEqualTo(90);
        assertThat(application.get("enteredPerSecond").size()).isEqualTo(30);
        assertThat(application.get("failedPerSecond").size()).isEqualTo(90);
    }

    @Test
    void from_anyFrame_hasTheFieldsOfTheWireContract() {
        var open = span("0000000000000001", Optional.empty(), MessageId.generate(), new Stage.Dispatch(COMMAND), new Outcome.Completed(Duration.ZERO));
        hub.receive(new SpanBatch(TICKETS, new InstanceName("tickets-1"), new SessionId("session-1"), List.of(open), List.of()));
        hub.receive(new SpanBatch(BILLING, new InstanceName("billing-1"), new SessionId("session-2"), List.of(
            span("0000000000000002", Optional.of("0000000000000001"), MessageId.generate(), new Stage.Dispatch(INVOICE),
                new Outcome.Completed(Duration.ZERO))), List.of()));

        var frame = json(OptionalLong.of(HEAP));

        assertThat(frame.propertyNames()).containsExactly("hubSession", "capturedAtMillis", "sequence", "endEpochSecond", "maxHeapBytes", "windowSeconds",
            "historySeconds", "settleSeconds", "retireAfterSeconds", "health", "applications");
        assertThat(frame.get("health").propertyNames()).containsExactly("budgetBytes", "queues", "counting", "join", "specimens", "historySeconds",
            "effectiveHistorySeconds", "foldsSooner", "linkHorizonSeconds", "refusedByApplicationLimit", "applications");
        assertThat(frame.get("health").get("queues").propertyNames()).containsExactly("bytes", "limitBytes");
        assertThat(frame.get("health").get("applications").get(0).propertyNames()).containsExactly("name", "reasons", "shedding", "countingBytes", "join",
            "specimens", "retained", "completeSinceMillis", "oldestRetainedMillis", "effectiveHorizonSeconds", "slowestK", "failuresK");
        assertThat(frame.get("applications").get(0).propertyNames()).containsExactly("name", "failedReceived", "instances", "types", "groups",
            "crossings", "dispatches", "handlings", "eventDelayP95Millis", "kinds", "enteredPerSecond", "failedPerSecond",
            "other", "provisionalFromEpochSecond");
        assertThat(frame.get("applications").get(0).get("instances").get(0).propertyNames()).containsExactly("name", "sessionId", "lastReportedAtMillis",
            "silent", "clockAhead", "exportLagMillis", "dispatches", "handlings");
        assertThat(frame.get("applications").get(0).get("other").propertyNames()).containsExactly("dispatchWindow", "dispatchHistory", "handlingWindow",
            "handlingHistory", "edgeHistory", "failureHistory");
        assertThat(frame.get("applications").get(0).get("types").get(0).propertyNames()).containsExactly("kind", "name", "entryPoint", "window", "history",
            "retained", "startedPerSecond");
        assertThat(frame.get("applications").get(0).get("crossings").get(0).propertyNames()).containsExactly("parentApplication", "link");
    }

    @Test
    void from_twoApplications_listsEachByNameWithItsCrossings() {
        var open = span("0000000000000001", Optional.empty(), MessageId.generate(), new Stage.Dispatch(COMMAND), new Outcome.Completed(Duration.ZERO));
        hub.receive(new SpanBatch(TICKETS, new InstanceName("tickets-1"), new SessionId("session-1"), List.of(open), List.of()));
        hub.receive(new SpanBatch(BILLING, new InstanceName("billing-1"), new SessionId("session-2"), List.of(
            span("0000000000000002", Optional.of("0000000000000001"), MessageId.generate(), new Stage.Dispatch(INVOICE),
                new Outcome.Completed(Duration.ZERO))), List.of()));

        var frame = json(OptionalLong.of(HEAP));

        var applications = frame.get("applications");
        assertThat(applications.valueStream().map(application -> application.get("name").asString())).containsExactly("billing", "tickets");
        var crossing = applications.get(0).get("crossings").get(0);
        assertThat(crossing.get("parentApplication").asString()).isEqualTo("tickets");
        assertThat(crossing.get("link").get("parent").get("name").asString()).isEqualTo(COMMAND.name());
        assertThat(crossing.get("link").get("child").get("name").asString()).isEqualTo(INVOICE.name());
        assertThat(crossing.get("link").get("history").asLong()).isEqualTo(1);
        assertThat(applications.get(1).get("crossings").isEmpty()).isTrue();
        assertThat(frame.get("health").get("applications").get(0).get("name").asString()).isEqualTo("billing");
        assertThat(frame.get("health").get("applications").get(0).get("retained").asInt()).isEqualTo(1);
    }

    @Test
    void from_noHandlings_leavesTheirLatencyOut() {
        hub.receive(new SpanBatch(TICKETS, new InstanceName("tickets-1"), new SessionId("session-1"), List.of(
            span("0000000000000001", Optional.empty(), MessageId.generate(), new Stage.Dispatch(COMMAND), new Outcome.Completed(Duration.ZERO))), List.of()));

        var statistics = json(OptionalLong.of(HEAP)).get("applications").get(0).get("handlings");

        assertThat(statistics.get(3).asLong()).isZero();
        assertThat(statistics.get(4).isNull()).isTrue();
    }

    // Statistics are positional: started, entered, failed, completed, average, floor, p50, p95, p99, max; a brief one ends at the p95.
    @Test
    void from_typeAndItsHistory_writesTheWindowWholeAndTheHistoryBrief() {
        hub.receive(new SpanBatch(TICKETS, new InstanceName("tickets-1"), new SessionId("session-1"), List.of(
            span("0000000000000001", Optional.empty(), MessageId.generate(), new Stage.Dispatch(COMMAND), new Outcome.Completed(Duration.ofMillis(2)))), List.of()));

        var type = json(OptionalLong.of(HEAP)).get("applications").get(0).get("types").get(0);

        assertThat(type.get("window").size()).isEqualTo(10);
        assertThat(type.get("window").valueStream().limit(5).map(JsonNode::asString)).containsExactly("1", "1", "0", "1", "2.0");
        assertThat(type.get("window").get(9).asDouble()).isEqualTo(2.0);
        assertThat(type.get("history").size()).isEqualTo(5);
        assertThat(type.get("history").valueStream().limit(4).map(JsonNode::asString)).containsExactly("1", "1", "0", "1");
    }

    @Test
    void figure_latencies_haveThreeSignificantMillisecondDigitsRoundedHalfUp() {
        assertThat(FrameView.figure(Optional.of(Duration.ofNanos(1_234_567)))).isEqualTo(1.23);
        assertThat(FrameView.figure(Optional.of(Duration.ofNanos(1_236_000)))).isEqualTo(1.24);
        assertThat(FrameView.figure(Optional.of(Duration.ofNanos(999_600)))).isEqualTo(1.0);
        assertThat(FrameView.figure(Optional.of(Duration.ofNanos(987_654_321)))).isEqualTo(988.0);
        assertThat(FrameView.figure(Optional.of(Duration.ofSeconds(12, 345_678_901)))).isEqualTo(12_300.0);
        assertThat(FrameView.figure(Optional.of(Duration.ofNanos(45)))).isEqualTo(0.000045);
        assertThat(FrameView.figure(Optional.of(Duration.ZERO))).isEqualTo(0.0);
        assertThat(FrameView.figure(Optional.empty())).isNull();
    }

    @Test
    void figure_anyLatency_isTheMillisecondsRoundedAsADecimalToThreeDigits() {
        var random = new Random(42);
        for (var draw = 0; draw < 100_000; draw++) {
            var nanos = (long) Math.pow(10, random.nextDouble() * 12);
            var halfway = (long) Math.pow(10, random.nextInt(10)) * (2 * random.nextInt(500, 5_000) + 1) / 2;
            for (var latency : List.of(nanos, halfway)) {
                var expected = new BigDecimal(latency / 1_000_000.0).round(new MathContext(3)).doubleValue();

                var figure = FrameView.figure(Optional.of(Duration.ofNanos(latency)));

                assertThat(figure).as("%d ns", latency).isEqualTo(expected);
            }
        }
    }

    @Test
    void from_jvmWithoutMaximumHeap_leavesTheMaximumHeapOut() {
        var frame = json(OptionalLong.empty());

        assertThat(frame.get("maxHeapBytes").isNull()).isTrue();
        assertThat(frame.get("applications").isEmpty()).isTrue();
    }

    private JsonNode json(OptionalLong maxHeapBytes) {
        var epoch = hub.epoch();
        return new ObjectMapper().valueToTree(FrameView.from(start, epoch.statistics(), epoch.health(), maxHeapBytes));
    }

    // The span links to the span of the invocation it continues.
    private MessageSpan span(String spanId, Optional<String> continues, MessageId messageId, Stage stage, Outcome outcome) {
        var delivery = stage instanceof Stage.Handling ? Optional.of(EventDelivery.SYNC) : Optional.<EventDelivery>empty();
        return new MessageSpan(SpanRef.of(TRACE, spanId), continues.map(continued -> SpanRef.of(TRACE, continued)), messageId, stage, delivery, start, "main", outcome);
    }
}
