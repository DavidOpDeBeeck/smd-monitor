package app.dodb.smd.monitor.hub.otlp;

import app.dodb.smd.api.event.delivery.EventDelivery;
import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.monitor.hub.Submission;
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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OtlpExportsTest {

    private static final String TRACE = "0af7651916cd43dd8448eb211c80319c";
    private static final Instant STARTED = Instant.parse("2026-10-22T10:00:00.123456789Z");
    private static final MessageType OPEN_TICKET = new MessageType(MessageKind.COMMAND, "app.OpenTicket");
    private static final MessageType TICKET_OPENED = new MessageType(MessageKind.EVENT, "app.TicketOpened");
    private static final MessageType FIND_CUSTOMER = new MessageType(MessageKind.QUERY, "app.FindCustomer");

    @Test
    void request_batchOfEveryKindOfSpan_decodesToTheSameBatch() {
        var command = span("00f067aa0ba902b7", Optional.empty(), new Stage.Dispatch(OPEN_TICKET), Optional.empty(), new Outcome.Completed(Duration.ofMillis(3)));
        var query = span("00f067aa0ba902b8", Optional.of(command.span()), new Stage.Dispatch(FIND_CUSTOMER), Optional.empty(),
            new Outcome.Completed(Duration.ofNanos(250_000)));
        var publication = span("00f067aa0ba902b9", Optional.of(command.span()), new Stage.Dispatch(TICKET_OPENED), Optional.empty(),
            new Outcome.Completed(Duration.ofMillis(1)));
        var handling = span("00f067aa0ba902ba", Optional.of(publication.span()), new Stage.Handling(TICKET_OPENED, "billing"),
            Optional.of(new EventDelivery("event-store")), new Outcome.Failed(Duration.ofMillis(7), "java.lang.IllegalStateException"));
        var send = new SinkSend(SpanRef.of(TRACE, "00f067aa0ba902bb"), TICKET_OPENED, new EventDelivery("kafka"), STARTED);
        var batch = new SpanBatch(new ApplicationName("tickets"), new InstanceName("tickets-01"), new SessionId(UUID.randomUUID().toString()),
            List.of(command, query, publication, handling), List.of(send));

        var decoded = decode(OtlpExports.request(batch));

        assertThat(decoded).containsExactly(batch);
    }

    @Test
    void rejectedSpans_partialSuccess_countsTheRejectedSpans() {
        var rejected = new Rejected();
        rejected.add(3, "SMD span requires the attribute smd.message.id");

        var count = OtlpExports.rejectedSpans(OtlpTraces.response(rejected));

        assertThat(count).isEqualTo(3);
    }

    @Test
    void rejectedSpans_fullSuccess_isZero() {
        var count = OtlpExports.rejectedSpans(OtlpTraces.response(new Rejected()));

        assertThat(count).isZero();
    }

    private static List<SpanBatch> decode(byte[] request) {
        var batches = new ArrayList<SpanBatch>();
        OtlpTraces.read(request).submit(batch -> {
            batches.add(batch);
            return new Submission.Accepted(Optional.empty());
        });
        return batches;
    }

    private static MessageSpan span(String spanId, Optional<SpanRef> continues, Stage stage, Optional<EventDelivery> delivery, Outcome outcome) {
        return new MessageSpan(SpanRef.of(TRACE, spanId), continues, new MessageId(UUID.randomUUID()), stage, delivery, STARTED, "http-nio-8080-exec-1",
            outcome);
    }
}
