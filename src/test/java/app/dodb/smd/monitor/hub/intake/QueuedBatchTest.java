package app.dodb.smd.monitor.hub.intake;

import app.dodb.smd.api.event.delivery.EventDelivery;
import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.monitor.hub.budget.Footprint;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class QueuedBatchTest {

    private static final ApplicationName TICKETS = new ApplicationName("ticket-service");
    private static final MessageType OPEN = new MessageType(MessageKind.COMMAND, "app.tickets.OpenTicket");
    private static final MessageType OPENED = new MessageType(MessageKind.EVENT, "app.tickets.TicketOpened");
    private static final Instant START = Instant.parse("2026-10-21T12:00:00.123456789Z");

    @Test
    void batch_spansAndSendsOfEveryShape_unpacksEqualToWhatWasPacked() {
        var open = new MessageSpan(ref(1), Optional.empty(), new MessageId(new UUID(-1L, 42L)), new Stage.Dispatch(OPEN), Optional.empty(), START, "http-1",
            new Outcome.Completed(Duration.ofNanos(1_500_001)));
        var opened = new MessageSpan(ref(2), Optional.of(ref(1)), new MessageId(UUID.randomUUID()), new Stage.Dispatch(OPENED), Optional.empty(),
            START.plusNanos(7), "http-1", new Outcome.Completed(Duration.ZERO));
        var handled = new MessageSpan(ref(3), Optional.of(ref(2)), new MessageId(UUID.randomUUID()), new Stage.Handling(OPENED, "ticket-view"),
            Optional.of(new EventDelivery("event-store")), START.plusSeconds(3), "worker-ü", new Outcome.Failed(Duration.ofSeconds(2, 9), "java.lang.IllegalStateException"));
        var sent = new SinkSend(ref(4), OPENED, new EventDelivery("outbox"), START.minusNanos(1));
        var batch = new SpanBatch(TICKETS, new InstanceName("tickets-1"), new SessionId("session-1"), List.of(open, opened, handled), List.of(sent));

        var unpacked = QueuedBatch.of(batch).batch();

        assertThat(unpacked).isEqualTo(batch);
    }

    @Test
    void size_spansAndSends_countsBothAsAdmissionDoes() {
        var batch = new SpanBatch(TICKETS, new InstanceName("tickets-1"), new SessionId("session-1"), spans(3, "http-1"),
            List.of(new SinkSend(ref(9), OPENED, EventDelivery.SYNC, START)));

        var queued = QueuedBatch.of(batch);

        assertThat(queued.size()).isEqualTo(4);
        assertThat(queued.application()).isEqualTo(TICKETS);
    }

    @Test
    void bytes_spansRepeatingANameInOtherStrings_holdTheNameOnce() {
        var shared = batch(spans(2, "http-1"));
        var copies = batch(List.of(span(1, "http-1"), span(2, new String("http-1".toCharArray()))));
        var distinct = batch(List.of(span(1, "http-1"), span(2, "http-2")));

        var sharedBytes = QueuedBatch.of(shared).bytes();
        var copiesBytes = QueuedBatch.of(copies).bytes();
        var distinctBytes = QueuedBatch.of(distinct).bytes();

        assertThat(copiesBytes).isEqualTo(sharedBytes);
        assertThat(distinctBytes).isEqualTo(sharedBytes + Footprint.queuedName("http-2"));
    }

    @Test
    void bytes_nameBeyondLatin1_takesTwoBytesACharacter() {
        assertThat(Footprint.queuedName("w".repeat(40))).isLessThan(Footprint.queuedName("ẃ".repeat(40)));
    }

    @Test
    void bytes_fullBatchOfAnInstance_takesAboutAQueuedSpanASpan() {
        var batch = batch(spans(512, "http-1"));

        var bytes = QueuedBatch.of(batch).bytes();

        assertThat(bytes / 512.0).isBetween((double) Footprint.QUEUED_SPAN, Footprint.QUEUED_SPAN * 1.05);
    }

    private static SpanBatch batch(List<MessageSpan> spans) {
        return new SpanBatch(TICKETS, new InstanceName("tickets-1"), new SessionId("session-1"), spans, List.of());
    }

    private static List<MessageSpan> spans(int count, String thread) {
        return IntStream.rangeClosed(1, count).mapToObj(index -> span(index, thread)).toList();
    }

    private static MessageSpan span(long id, String thread) {
        return new MessageSpan(ref(id), Optional.empty(), new MessageId(new UUID(0, id)), new Stage.Dispatch(OPEN), Optional.empty(), START.plusMillis(id),
            thread, new Outcome.Completed(Duration.ofMillis(3)));
    }

    private static SpanRef ref(long id) {
        return new SpanRef(7, id, id * 31);
    }
}
