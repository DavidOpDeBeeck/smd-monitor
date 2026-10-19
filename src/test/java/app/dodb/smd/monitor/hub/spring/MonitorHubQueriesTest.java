package app.dodb.smd.monitor.hub.spring;

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
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MonitorHubQueriesTest {

    private static final ApplicationName TICKETS = new ApplicationName("tickets");
    private static final String TRACE = "0000000000000000000000000000000a";

    private final SynchronousHub hub = new SynchronousHub(HubConfig.withDefaults().build());
    private final MonitorHubQueries queries = new MonitorHubQueries(hub.hub());

    @Test
    void answer_sameQueryTwiceInOneEpoch_computesItOnce() {
        hub.epoch();
        var computed = new AtomicInteger();

        var first = queries.answer(List.of("query"), epoch -> ResponseEntity.ok(String.valueOf(computed.incrementAndGet())));
        var second = queries.answer(List.of("query"), epoch -> ResponseEntity.ok(String.valueOf(computed.incrementAndGet())));

        assertThat(computed).hasValue(1);
        assertThat(second).isSameAs(first);
    }

    @Test
    void answer_sameQueryInALaterEpoch_computesItAgain() {
        hub.epoch();
        var computed = new AtomicInteger();
        queries.answer(List.of("query"), epoch -> ResponseEntity.ok(String.valueOf(computed.incrementAndGet())));
        hub.epoch();

        var later = queries.answer(List.of("query"), epoch -> ResponseEntity.ok(String.valueOf(computed.incrementAndGet())));

        assertThat(computed).hasValue(2);
        assertThat(later.getBody()).isEqualTo("2");
    }

    @Test
    void tape_anyAnswer_namesTheProtocolAndTheBuild() {
        hub.epoch();

        var response = queries.tape(Optional.empty(), null, null, null, null, "", 0, Optional.empty(), 100);

        assertThat(response.getHeaders().getFirst(MonitorHubQueries.PROTOCOL_HEADER)).isEqualTo(String.valueOf(Protocol.VERSION));
        assertThat(response.getHeaders().getFirst(MonitorHubQueries.BUILD_HEADER)).isEqualTo(Protocol.build());
    }

    @Test
    void trace_askedAgainWithItsETagAfterTheHubRetainedMore_answersNotModified() {
        receive(span("0000000000000001", Optional.empty()));
        var first = queries.trace(1, null);
        receive(span("0000000000000002", Optional.empty()));
        hub.epoch();

        var again = queries.trace(1, first.getHeaders().getETag());

        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(first.getHeaders().getETag()).isNotBlank();
        assertThat(again.getStatusCode().value()).isEqualTo(304);
        assertThat(again.getBody()).isNull();
    }

    @Test
    void trace_askedAgainAfterItGrew_answersTheTraceWithAnotherETag() {
        receive(span("0000000000000001", Optional.empty()));
        var first = queries.trace(1, null);
        receive(span("0000000000000002", Optional.of("0000000000000001")));
        hub.epoch();

        var again = queries.trace(1, first.getHeaders().getETag());

        assertThat(again.getStatusCode().value()).isEqualTo(200);
        assertThat(again.getHeaders().getETag()).isNotEqualTo(first.getHeaders().getETag());
    }

    @Test
    void invocation_askedAgainWithItsETagUnchanged_answersNotModified() {
        receive(span("0000000000000001", Optional.empty()));
        var first = queries.invocation(1, null);
        receive(span("0000000000000005", Optional.empty()));
        hub.epoch();

        var again = queries.invocation(1, first.getHeaders().getETag());

        assertThat(again.getStatusCode().value()).isEqualTo(304);
    }

    @Test
    void failures_causeOfAGroupsHandling_answersItsRetainedOccurrences() {
        receive(new MessageSpan(SpanRef.of(TRACE, "0000000000000001"), Optional.empty(), MessageId.generate(),
            new Stage.Handling(new MessageType(MessageKind.EVENT, "TicketOpened"), "ticket-view"), Optional.of(EventDelivery.SYNC), Instant.now(), "main",
            new Outcome.Failed(Duration.ofMillis(1), "java.lang.IllegalStateException")));

        var response = queries.failures(List.of("tickets::EVENT:TicketOpened|java.lang.IllegalStateException|handling:ticket-view"));

        assertThat(response.getBody()).contains("\"retained\":1", "\"processingGroup\":\"ticket-view\"");
    }

    @Test
    void failures_causeWithoutItsStage_rejectsWithTheExpectedForm() {
        hub.epoch();

        assertThatThrownBy(() -> queries.failures(List.of("tickets::COMMAND:OpenTicket|java.lang.IllegalStateException")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("application::KIND:class|failure type|dispatch", "'tickets::COMMAND:OpenTicket|java.lang.IllegalStateException'");
    }

    private void receive(MessageSpan span) {
        hub.receive(new SpanBatch(TICKETS, new InstanceName("tickets-1"), new SessionId("session"), List.of(span), List.of()));
        hub.epoch();
    }

    private static MessageSpan span(String spanId, Optional<String> continues) {
        return new MessageSpan(SpanRef.of(TRACE, spanId), continues.map(parent -> SpanRef.of(TRACE, parent)), MessageId.generate(),
            new Stage.Dispatch(new MessageType(MessageKind.COMMAND, "OpenTicket")), Optional.empty(), Instant.now(), "main",
            new Outcome.Completed(Duration.ofMillis(1)));
    }
}
