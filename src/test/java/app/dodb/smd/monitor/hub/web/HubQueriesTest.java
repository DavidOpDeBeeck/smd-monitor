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
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
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

    private final HubQueries queries = new HubQueries(hub.hub());
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

    private void receive(MessageSpan span) {
        receive(TICKETS, span);
    }

    private void receive(ApplicationName application, MessageSpan span) {
        hub.receive(new SpanBatch(application, new InstanceName("tickets-1"), new SessionId("session"), List.of(span), List.of()));
        hub.epoch();
    }

    private static MessageSpan span(String spanId, Optional<String> continues) {
        return new MessageSpan(SpanRef.of(TRACE, spanId), continues.map(parent -> SpanRef.of(TRACE, parent)), MessageId.generate(),
            new Stage.Dispatch(new MessageType(MessageKind.COMMAND, "OpenTicket")), Optional.empty(), Instant.now(), "main",
            new Outcome.Completed(Duration.ofMillis(1)));
    }
}
