package app.dodb.smd.monitor.hub.web;

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
import app.dodb.smd.monitor.json.Protocol;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HubQueriesTest {

    private static final ApplicationName TICKETS = new ApplicationName("tickets");
    private static final ApplicationName BILLING = new ApplicationName("billing");
    private static final String TRACE = "0000000000000000000000000000000a";

    private final SynchronousHub hub = new SynchronousHub(HubConfig.withDefaults().build());
    private static final QueryFilter EVERY_INVOCATION = new QueryFilter(null, List.of(), List.of(), List.of(), List.of(), null);

    private final HubQueries queries = new HubQueries(hub.hub(), 8);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void answer_sameQueryTwiceInOneEpoch_computesItOnce() {
        hub.epoch();
        var computed = new AtomicInteger();

        var first = queries.answer(List.of("query"), epoch -> QueryAnswer.versioned(String.valueOf(computed.incrementAndGet()), "version"));
        var second = queries.answer(List.of("query"), epoch -> QueryAnswer.versioned(String.valueOf(computed.incrementAndGet()), "version"));

        assertThat(computed).hasValue(1);
        assertThat(second).isSameAs(first);
    }

    @Test
    void answer_sameQueryInALaterEpoch_computesItAgain() {
        hub.epoch();
        var computed = new AtomicInteger();
        queries.answer(List.of("query"), epoch -> QueryAnswer.versioned(String.valueOf(computed.incrementAndGet()), "version"));
        hub.epoch();

        var later = queries.answer(List.of("query"), epoch -> QueryAnswer.versioned(String.valueOf(computed.incrementAndGet()), "version"));

        assertThat(computed).hasValue(2);
        assertThat(later.body()).isEqualTo("2");
    }

    @Test
    void tape_anyAnswer_namesTheProtocolAndTheBuild() {
        hub.epoch();

        var response = queries.tape(EVERY_INVOCATION, null, null, null);

        assertThat(response.headers()).containsEntry(QueryAnswer.PROTOCOL_HEADER, String.valueOf(Protocol.VERSION))
            .containsEntry(QueryAnswer.BUILD_HEADER, Protocol.build());
    }

    @Test
    void tape_limitNotANumber_failsNamingTheParameter() {
        hub.epoch();

        assertThatThrownBy(() -> queries.tape(EVERY_INVOCATION, null, null, "many"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("The query parameter limit must be a whole number; got 'many'");
    }

    @Test
    void tape_minimumDurationNegative_failsNamingTheParameter() {
        hub.epoch();

        assertThatThrownBy(() -> queries.tape(EVERY_INVOCATION, "-1", null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("The query parameter minDurationMillis must be a number of milliseconds of at least 0; got '-1'");
    }

    @Test
    void durations_bucketsMissing_failsNamingTheParameter() {
        hub.epoch();

        assertThatThrownBy(() -> queries.durations(EVERY_INVOCATION, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("The query parameter buckets is required");
    }

    @Test
    void trace_sequenceNotANumber_failsNamingTheExpectedForm() {
        hub.epoch();

        assertThatThrownBy(() -> queries.trace("latest"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("An invocation or a trace is named by its sequence number; got 'latest'");
    }

    @Test
    void traces_unknownOrder_failsNamingTheOrders() {
        hub.epoch();

        assertThatThrownBy(() -> queries.traces(EVERY_INVOCATION, null, "sideways", null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContainingAll("'SIDEWAYS'", "NEWEST");
    }

    @Test
    void trace_askedAgainWithItsETagAfterTheHubRetainedMore_answersNotModified() {
        receive(span("0000000000000001", Optional.empty()));
        var first = queries.trace("1");
        receive(span("0000000000000002", Optional.empty()));
        hub.epoch();

        var again = queries.trace("1").unlessHeld(first.eTag());

        assertThat(first.status()).isEqualTo(200);
        assertThat(first.eTag()).isNotBlank();
        assertThat(again.status()).isEqualTo(304);
        assertThat(again.body()).isNull();
    }

    @Test
    void trace_askedAgainAfterItGrew_answersTheTraceWithAnotherETag() {
        receive(span("0000000000000001", Optional.empty()));
        var first = queries.trace("1");
        receive(span("0000000000000002", Optional.of("0000000000000001")));
        hub.epoch();

        var again = queries.trace("1").unlessHeld(first.eTag());

        assertThat(again.status()).isEqualTo(200);
        assertThat(again.eTag()).isNotEqualTo(first.eTag());
    }

    @Test
    void invocation_askedAgainWithItsETagUnchanged_answersNotModified() {
        receive(span("0000000000000001", Optional.empty()));
        var first = queries.invocation("1");
        receive(span("0000000000000005", Optional.empty()));
        hub.epoch();

        var again = queries.invocation("1").unlessHeld(first.eTag());

        assertThat(again.status()).isEqualTo(304);
    }

    @Test
    void failures_causeOfAGroupsHandling_answersItsRetainedOccurrences() {
        receive(new MessageSpan(SpanRef.of(TRACE, "0000000000000001"), Optional.empty(), MessageId.generate(),
            new Stage.Handling(new MessageType(MessageKind.EVENT, "TicketOpened"), "ticket-view"), Optional.of(EventDelivery.SYNC), Instant.now(), "main",
            new Outcome.Failed(Duration.ofMillis(1), "java.lang.IllegalStateException")));

        var response = queries.failures(List.of("tickets::EVENT:TicketOpened|java.lang.IllegalStateException|handling:ticket-view"));

        assertThat(response.body()).contains("\"retained\":1", "\"processingGroup\":\"ticket-view\"");
    }

    @Test
    void failures_causeWithoutItsStage_failsNamingTheExpectedForm() {
        hub.epoch();

        assertThatThrownBy(() -> queries.failures(List.of("tickets::COMMAND:OpenTicket|java.lang.IllegalStateException")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContainingAll("application::KIND:class|failure type|dispatch", "'tickets::COMMAND:OpenTicket|java.lang.IllegalStateException'");
    }

    @Test
    void failures_causeOfAnApplicationWithSeparatorsInItsName_answersItsRetainedOccurrencesByTheEncodedName() {
        receive(new ApplicationName("Order Service:v2 | eu@1+"), new MessageSpan(SpanRef.of(TRACE, "0000000000000001"), Optional.empty(), MessageId.generate(),
            new Stage.Dispatch(new MessageType(MessageKind.COMMAND, "OpenTicket")), Optional.empty(), Instant.now(), "main",
            new Outcome.Failed(Duration.ofMillis(1), "java.lang.IllegalStateException")));

        var response = queries.failures(List.of("Order%20Service%3Av2%20%7C%20eu%401%2B::COMMAND:OpenTicket|java.lang.IllegalStateException|dispatch"));

        assertThat(response.body()).contains("\"retained\":1", "\"application\":\"Order Service:v2 | eu@1+\"");
    }

    @Test
    void failures_causeWithAMalformedEncodedApplication_failsNamingTheExpectedForm() {
        hub.epoch();

        assertThatThrownBy(() -> queries.failures(List.of("Order%2::COMMAND:OpenTicket|java.lang.IllegalStateException|dispatch")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContainingAll("percent-encoded", "'Order%2::COMMAND:OpenTicket'");
    }

    @Test
    void tape_askedAgainWithItsETagWhileTheHubRetainedOnlyInAnotherScope_answersNotModified() {
        var tickets = new QueryFilter(TICKETS.value(), List.of(), List.of(), List.of(), List.of(), null);
        receive(span("0000000000000001", Optional.empty()));
        var first = queries.tape(tickets, null, null, null);
        receive(new ApplicationName("billing"), span("0000000000000002", Optional.empty()));

        var answered = queries.tape(tickets, null, null, null);
        var again = answered.unlessHeld(first.eTag());

        assertThat(answered.body()).isNotEqualTo(first.body());
        assertThat(answered.eTag()).isEqualTo(first.eTag());
        assertThat(again.status()).isEqualTo(304);
        assertThat(again.body()).isNull();
    }

    @Test
    void tape_askedAgainAfterAnInvocationInItsScope_answersTheTapeWithAnotherETag() {
        receive(span("0000000000000001", Optional.empty()));
        var first = queries.tape(EVERY_INVOCATION, null, null, null);
        receive(span("0000000000000002", Optional.empty()));

        var again = queries.tape(EVERY_INVOCATION, null, null, null).unlessHeld(first.eTag());

        assertThat(again.status()).isEqualTo(200);
        assertThat(again.eTag()).isNotEqualTo(first.eTag());
    }

    @Test
    void failures_askedAgainWhileTheCauseDidNotFailAgain_answersNotModified() {
        var cause = List.of("tickets::EVENT:TicketOpened|java.lang.IllegalStateException|handling:ticket-view");
        receive(new MessageSpan(SpanRef.of(TRACE, "0000000000000001"), Optional.empty(), MessageId.generate(),
            new Stage.Handling(new MessageType(MessageKind.EVENT, "TicketOpened"), "ticket-view"), Optional.of(EventDelivery.SYNC), Instant.now(), "main",
            new Outcome.Failed(Duration.ofMillis(1), "java.lang.IllegalStateException")));
        var first = queries.failures(cause);
        receive(span("0000000000000002", Optional.empty()));

        var again = queries.failures(cause).unlessHeld(first.eTag());

        assertThat(again.status()).isEqualTo(304);
    }

    @Test
    void timeline_anyAnswer_carriesNoETag() {
        receive(span("0000000000000001", Optional.empty()));

        var answer = queries.timeline(EVERY_INVOCATION, null, "60000", "100");

        assertThat(answer.status()).isEqualTo(200);
        assertThat(answer.eTag()).isNull();
    }

    @Test
    void flow_scopeNamingAnApplication_answersThatApplicationsFlowOnly() {
        receive(TICKETS, span("0000000000000001", Optional.empty()));
        receive(BILLING, span("0000000000000002", Optional.empty()));

        var flow = mapper.readTree(queries.flow("billing").body());

        assertThat(flow.get("applications").valueStream().map(application -> application.get("name").asString())).containsExactly("billing");
    }

    @Test
    void flow_noScope_answersEveryApplication() {
        receive(TICKETS, span("0000000000000001", Optional.empty()));
        receive(BILLING, span("0000000000000002", Optional.empty()));

        var flow = mapper.readTree(queries.flow(null).body());

        assertThat(flow.get("applications").valueStream().map(application -> application.get("name").asString())).containsExactlyInAnyOrder("billing", "tickets");
    }

    @Test
    void handlings_eventHandledInAScope_answersWhatEachGroupsHandlingCounted() {
        receive(TICKETS, handling("0000000000000001", "ticket-view"));
        receive(BILLING, span("0000000000000002", Optional.empty()));

        var handlings = mapper.readTree(queries.handlings("tickets").body()).get("handlings");

        assertThat(handlings.size()).isEqualTo(1);
        assertThat(handlings.get(0).get("processingGroup").asString()).isEqualTo("ticket-view");
        assertThat(handlings.get(0).get("name").asString()).isEqualTo("TicketOpened");
        assertThat(handlings.get(0).get("history").get(0).asLong()).isEqualTo(1);
    }

    @Test
    void type_commandThatFailed_answersItsFailuresSecondBySecond() {
        receive(TICKETS, new MessageSpan(SpanRef.of(TRACE, "0000000000000001"), Optional.empty(), MessageId.generate(),
            new Stage.Dispatch(new MessageType(MessageKind.COMMAND, "OpenTicket")), Optional.empty(), Instant.now().minusSeconds(10), "main",
            new Outcome.Failed(Duration.ofMillis(1), "java.lang.IllegalStateException")));

        var failed = mapper.readTree(queries.type("tickets::COMMAND:OpenTicket").body()).get("failedPerSecond");

        assertThat(failed.valueStream().mapToLong(JsonNode::asLong).sum()).isEqualTo(1);
    }

    @Test
    void type_eventHandledByAGroup_answersThatGroupsHandling() {
        receive(TICKETS, handling("0000000000000001", "ticket-view"));

        var event = mapper.readTree(queries.type("tickets::EVENT:TicketOpened").body());

        assertThat(event.get("handlings").get(0).get("processingGroup").asString()).isEqualTo("ticket-view");
        assertThat(event.get("handlings").get(0).get("history").get(0).asLong()).isEqualTo(1);
    }

    // The type inspector shows the links into the type and out of it, across applications too, which the frame no longer carries.
    @Test
    void type_commandContinuedInAnotherApplication_answersTheLinkOutOfItAndIntoTheOther() {
        receive(TICKETS, span("0000000000000001", Optional.empty()));
        receive(BILLING, new MessageSpan(SpanRef.of(TRACE, "0000000000000002"), Optional.of(SpanRef.of(TRACE, "0000000000000001")), MessageId.generate(),
            new Stage.Dispatch(new MessageType(MessageKind.COMMAND, "CreateInvoice")), Optional.empty(), Instant.now(), "main",
            new Outcome.Completed(Duration.ofMillis(1))));

        var open = mapper.readTree(queries.type("tickets::COMMAND:OpenTicket").body());
        var invoice = mapper.readTree(queries.type("billing::COMMAND:CreateInvoice").body());

        assertThat(open.get("outgoing").get(0).get("childApplication").asString()).isEqualTo("billing");
        assertThat(open.get("outgoing").get(0).get("child").get("name").asString()).isEqualTo("CreateInvoice");
        assertThat(open.get("incoming").isEmpty()).isTrue();
        assertThat(invoice.get("incoming").get(0).get("parentApplication").asString()).isEqualTo("tickets");
        assertThat(invoice.get("incoming").get(0).get("parent").get("name").asString()).isEqualTo("OpenTicket");
    }

    @Test
    void causes_failedHandling_answersItsStageExceptionTypeAndSeconds() {
        receive(failed("0000000000000001", new Stage.Handling(new MessageType(MessageKind.EVENT, "TicketOpened"), "ticket-view"), settled()));

        var answer = mapper.readTree(queries.causes(null, "recent", null).body());

        var cause = answer.get("causes").get(0);
        assertThat(answer.get("total").asInt()).isEqualTo(1);
        assertThat(cause.propertyNames()).containsExactly("application", "kind", "type", "handling", "processingGroup", "failureType", "window", "history",
            "windowPerSecond", "started");
        assertThat(cause.get("application").asString()).isEqualTo("tickets");
        assertThat(cause.get("started").asLong()).isEqualTo(1);
        assertThat(cause.get("processingGroup").asString()).isEqualTo("ticket-view");
        assertThat(cause.get("window").asLong()).isEqualTo(1);
        assertThat(cause.get("windowPerSecond").valueStream().mapToLong(JsonNode::asLong).sum()).isEqualTo(1);
    }

    @Test
    void causes_recentWithALimit_answersTheMostFailedInTheWindowFirstOutOfAllThatFailed() {
        receive(failed("0000000000000001", new Stage.Dispatch(new MessageType(MessageKind.COMMAND, "OpenTicket")), settled()));
        receive(failed("0000000000000002", new Stage.Dispatch(new MessageType(MessageKind.COMMAND, "CloseTicket")), settled()));
        receive(failed("0000000000000003", new Stage.Dispatch(new MessageType(MessageKind.COMMAND, "CloseTicket")), settled()));

        var answer = mapper.readTree(queries.causes(null, "recent", "1").body());

        assertThat(answer.get("total").asInt()).isEqualTo(2);
        assertThat(answer.get("causes").size()).isEqualTo(1);
        assertThat(answer.get("causes").get(0).get("type").asString()).isEqualTo("CloseTicket");
    }

    @Test
    void type_anyAnswer_namesTheSecondItsSeriesEndAt() {
        receive(dispatch("0000000000000001", "OpenTicket"));

        var answer = mapper.readTree(queries.type("tickets::COMMAND:OpenTicket").body());

        assertThat(answer.get("endEpochSecond").asLong()).isEqualTo(hub.statistics().end().getEpochSecond());
    }

    @Test
    void causes_askedAgainASecondLaterWhileNothingFailed_answersNotModified() {
        var clock = new SteppedClock(Instant.now());
        var stepped = new SynchronousHub(HubConfig.withDefaults().build(), clock);
        var steppedQueries = new HubQueries(stepped.hub(), 8);
        stepped.receive(new SpanBatch(TICKETS, new InstanceName("tickets-1"), new SessionId("session"),
            List.of(failed("0000000000000001", new Stage.Dispatch(new MessageType(MessageKind.COMMAND, "OpenTicket")), clock.instant().minusSeconds(120))),
            List.of()));
        stepped.epoch();
        var first = steppedQueries.causes(null, "ranked", null);
        clock.advance(Duration.ofSeconds(1));
        stepped.epoch();

        var again = steppedQueries.causes(null, "ranked", null);

        assertThat(again.unlessHeld(first.eTag()).status()).isEqualTo(304);
        assertThat(mapper.readTree(again.body()).get("endEpochSecond").asLong()).isEqualTo(mapper.readTree(first.body()).get("endEpochSecond").asLong() + 1);
    }

    // The overview lists what fails now, the failures view what failed over the history.
    @Test
    void causes_failedOnlyBeforeTheWindow_answersItRankedButNotRecent() {
        receive(failed("0000000000000001", new Stage.Dispatch(new MessageType(MessageKind.COMMAND, "OpenTicket")), Instant.now().minusSeconds(120)));

        var recent = mapper.readTree(queries.causes(null, "recent", null).body());
        var ranked = mapper.readTree(queries.causes(null, "ranked", null).body());

        assertThat(recent.get("total").asInt()).isZero();
        assertThat(ranked.get("total").asInt()).isEqualTo(1);
        assertThat(ranked.get("causes").get(0).get("history").asLong()).isEqualTo(1);
    }

    @Test
    void causes_scopeNamingAnApplication_answersThatApplicationsCausesOnly() {
        receive(TICKETS, failed("0000000000000001", new Stage.Dispatch(new MessageType(MessageKind.COMMAND, "OpenTicket")), settled()));
        receive(BILLING, failed("0000000000000002", new Stage.Dispatch(new MessageType(MessageKind.COMMAND, "CreateInvoice")), settled()));

        var causes = mapper.readTree(queries.causes("billing", "recent", null).body()).get("causes");

        assertThat(causes.size()).isEqualTo(1);
        assertThat(causes.get(0).get("application").asString()).isEqualTo("billing");
    }

    @Test
    void causes_unknownOrder_failsNamingTheOrders() {
        hub.epoch();

        assertThatThrownBy(() -> queries.causes(null, "newest", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("The query parameter order must be recent or ranked; got 'newest'");
    }

    @Test
    void causes_limitBelowOne_failsNamingTheParameter() {
        hub.epoch();

        assertThatThrownBy(() -> queries.causes(null, "ranked", "0"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("The query parameter limit must be at least 1; got '0'");
    }

    @Test
    void typeFigures_limitBelowOne_failsNamingTheParameter() {
        hub.epoch();

        assertThatThrownBy(() -> queries.typeFigures(null, List.of(), List.of(), "rate", "-1"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("The query parameter limit must be at least 1; got '-1'");
    }

    @Test
    void causes_orderMissing_failsNamingTheParameter() {
        hub.epoch();

        assertThatThrownBy(() -> queries.causes(null, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("order");
    }

    @Test
    void typeFigures_rateWithALimit_answersTheBusiestFirstOutOfAllSelected() {
        receive(dispatch("0000000000000001", "OpenTicket"));
        receive(dispatch("0000000000000002", "CloseTicket"));
        receive(dispatch("0000000000000003", "CloseTicket"));

        var answer = mapper.readTree(queries.typeFigures(null, List.of(), List.of(), "rate", "1").body());

        assertThat(answer.get("total").asInt()).isEqualTo(2);
        assertThat(answer.get("types").size()).isEqualTo(1);
        assertThat(answer.get("types").get(0).get("name").asString()).isEqualTo("CloseTicket");
        assertThat(answer.get("types").get(0).get("window").get(0).asLong()).isEqualTo(2);
    }

    @Test
    void typeFigures_anyType_hasTheFieldsOfTheWireContract() {
        receive(dispatch("0000000000000001", "OpenTicket"));

        var type = mapper.readTree(queries.typeFigures(null, List.of(), List.of(), "name", null).body()).get("types").get(0);

        assertThat(type.propertyNames()).containsExactly("application", "kind", "name", "entryPoint", "window", "history", "retained", "startedPerSecond");
    }

    // Statistics are positional: started, entered, failed, completed, average, floor, p50, p95, p99, max; a brief one ends at the p95.
    @Test
    void typeFigures_completedDispatch_writesTheWindowWholeAndTheHistoryBrief() {
        receive(new MessageSpan(SpanRef.of(TRACE, "0000000000000001"), Optional.empty(), MessageId.generate(),
            new Stage.Dispatch(new MessageType(MessageKind.COMMAND, "OpenTicket")), Optional.empty(), settled(), "main", new Outcome.Completed(Duration.ofMillis(2))));

        var type = mapper.readTree(queries.typeFigures(null, List.of(), List.of(), "rate", null).body()).get("types").get(0);

        assertThat(type.get("window").size()).isEqualTo(10);
        assertThat(type.get("window").valueStream().limit(5).map(JsonNode::asString)).containsExactly("1", "1", "0", "1", "2.0");
        assertThat(type.get("window").get(9).asDouble()).isEqualTo(2.0);
        assertThat(type.get("history").size()).isEqualTo(5);
        assertThat(type.get("history").valueStream().limit(4).map(JsonNode::asString)).containsExactly("1", "1", "0", "1");
    }

    @Test
    void typeFigures_kindSelected_answersTheTypesOfThatKindOnly() {
        receive(dispatch("0000000000000001", new MessageType(MessageKind.COMMAND, "OpenTicket")));
        receive(dispatch("0000000000000002", new MessageType(MessageKind.QUERY, "FindTicket")));

        var types = mapper.readTree(queries.typeFigures("tickets", List.of("QUERY"), List.of(), "name", null).body()).get("types");

        assertThat(types.size()).isEqualTo(1);
        assertThat(types.get(0).get("kind").asString()).isEqualTo("QUERY");
    }

    @Test
    void typeFigures_typeSelected_answersThatTypeOnly() {
        receive(TICKETS, dispatch("0000000000000001", "OpenTicket"));
        receive(BILLING, dispatch("0000000000000002", "OpenTicket"));

        var types = mapper.readTree(queries.typeFigures(null, List.of(), List.of("billing::COMMAND:OpenTicket"), "rate", null).body()).get("types");

        assertThat(types.size()).isEqualTo(1);
        assertThat(types.get(0).get("application").asString()).isEqualTo("billing");
    }

    @Test
    void typeFigures_unknownSort_failsNamingTheSorts() {
        hub.epoch();

        assertThatThrownBy(() -> queries.typeFigures(null, List.of(), List.of(), "busiest", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("The query parameter sort must be rate, failed, failures, p50, p95, p99, or name; got 'busiest'");
    }

    private static final class SteppedClock extends Clock {

        private Instant now;

        SteppedClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private void receive(MessageSpan span) {
        receive(TICKETS, span);
    }

    private void receive(ApplicationName application, MessageSpan span) {
        hub.receive(new SpanBatch(application, new InstanceName("tickets-1"), new SessionId("session"), List.of(span), List.of()));
        hub.epoch();
    }

    private static MessageSpan handling(String spanId, String processingGroup) {
        return new MessageSpan(SpanRef.of(TRACE, spanId), Optional.empty(), MessageId.generate(),
            new Stage.Handling(new MessageType(MessageKind.EVENT, "TicketOpened"), processingGroup), Optional.of(EventDelivery.SYNC),
            // Old enough that its second settled into the history.
            Instant.now().minusSeconds(10), "main",
            new Outcome.Completed(Duration.ofMillis(1)));
    }

    // Old enough that its second settled into the counts.
    private static Instant settled() {
        return Instant.now().minusSeconds(10);
    }

    private static MessageSpan failed(String spanId, Stage stage, Instant startedAt) {
        var delivery = stage instanceof Stage.Handling ? Optional.of(EventDelivery.SYNC) : Optional.<EventDelivery>empty();
        return new MessageSpan(SpanRef.of(TRACE, spanId), Optional.empty(), MessageId.generate(), stage, delivery, startedAt, "main",
            new Outcome.Failed(Duration.ofMillis(1), "java.lang.IllegalStateException"));
    }

    private static MessageSpan dispatch(String spanId, String command) {
        return dispatch(spanId, new MessageType(MessageKind.COMMAND, command));
    }

    private static MessageSpan dispatch(String spanId, MessageType type) {
        return new MessageSpan(SpanRef.of(TRACE, spanId), Optional.empty(), MessageId.generate(), new Stage.Dispatch(type), Optional.empty(), settled(), "main",
            new Outcome.Completed(Duration.ofMillis(1)));
    }

    private static MessageSpan span(String spanId, Optional<String> continues) {
        return new MessageSpan(SpanRef.of(TRACE, spanId), continues.map(parent -> SpanRef.of(TRACE, parent)), MessageId.generate(),
            new Stage.Dispatch(new MessageType(MessageKind.COMMAND, "OpenTicket")), Optional.empty(), Instant.now(), "main",
            new Outcome.Completed(Duration.ofMillis(1)));
    }
}
