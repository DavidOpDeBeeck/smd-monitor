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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

// A batch as it waits in its application's intake queue: its spans and sends packed into a few columns of numbers and names, every name the batch
// repeats held once, so a waiting span takes a fraction of the objects it was decoded into, and the queues hold that many more within their share
// of the memory budget. The receiving thread packs it and the writer unpacks it; it never changes in between.
final class QueuedBatch {

    private static final MessageKind[] KINDS = MessageKind.values();
    private static final int SPAN_NUMBERS = 10;
    private static final int SPAN_NAMES = 5;
    private static final int SEND_NUMBERS = 4;
    private static final byte CONTINUES = 1;
    private static final byte HANDLING = 2;
    private static final byte DELIVERED = 4;
    private static final byte FAILED = 8;

    private final ApplicationName application;
    private final InstanceName instance;
    private final SessionId session;
    // Per span: its span, the span it continues, its message ID, its start's seconds, and its duration's seconds; the nanoseconds of both; its kind
    // and flags; and its type, processing group, delivery, thread, and failure type.
    private final long[] numbers;
    private final int[] nanos;
    private final byte[] codes;
    private final String[] names;
    // Per send: its span and its start's seconds; their nanoseconds; and its event's and its delivery's names.
    private final long[] sendNumbers;
    private final int[] sendNanos;
    private final String[] sendNames;
    private final int spans;
    private final int sends;
    private final long bytes;

    private QueuedBatch(SpanBatch batch) {
        application = batch.application();
        instance = batch.instance();
        session = batch.session();
        spans = batch.spans().size();
        sends = batch.sends().size();
        numbers = new long[SPAN_NUMBERS * spans];
        nanos = new int[2 * spans];
        codes = new byte[2 * spans];
        names = new String[SPAN_NAMES * spans];
        sendNumbers = new long[SEND_NUMBERS * sends];
        sendNanos = new int[sends];
        sendNames = new String[2 * sends];
        var held = new HashMap<String, String>();
        for (var index = 0; index < spans; index++) {
            pack(index, batch.spans().get(index), held);
        }
        for (var index = 0; index < sends; index++) {
            pack(index, batch.sends().get(index), held);
        }
        bytes = Footprint.queuedBatch(spans, sends) + Footprint.queuedName(application.value()) + Footprint.queuedName(instance.value())
            + Footprint.queuedName(session.value()) + held.keySet().stream().mapToLong(Footprint::queuedName).sum();
    }

    static QueuedBatch of(SpanBatch batch) {
        return new QueuedBatch(batch);
    }

    ApplicationName application() {
        return application;
    }

    // The spans and sends, as admission counts them.
    int size() {
        return spans + sends;
    }

    // The heap the batch takes while it waits, with the names it holds.
    long bytes() {
        return bytes;
    }

    // The batch as it was packed, with spans and sends equal to those it was packed from.
    SpanBatch batch() {
        var unpacked = new ArrayList<MessageSpan>(spans);
        for (var index = 0; index < spans; index++) {
            unpacked.add(span(index));
        }
        var unpackedSends = new ArrayList<SinkSend>(sends);
        for (var index = 0; index < sends; index++) {
            unpackedSends.add(send(index));
        }
        return new SpanBatch(application, instance, session, unpacked, unpackedSends);
    }

    private void pack(int index, MessageSpan span, Map<String, String> held) {
        var number = SPAN_NUMBERS * index;
        var name = SPAN_NAMES * index;
        packRef(numbers, number, span.span());
        span.continues().ifPresent(continued -> packRef(numbers, number + 3, continued));
        numbers[number + 6] = span.messageId().value().getMostSignificantBits();
        numbers[number + 7] = span.messageId().value().getLeastSignificantBits();
        numbers[number + 8] = span.startedAt().getEpochSecond();
        numbers[number + 9] = span.outcome().duration().getSeconds();
        nanos[2 * index] = span.startedAt().getNano();
        nanos[2 * index + 1] = span.outcome().duration().getNano();
        codes[2 * index] = (byte) span.stage().type().kind().ordinal();
        var flags = span.continues().isPresent() ? CONTINUES : 0;
        names[name] = held(held, span.stage().type().name());
        if (span.stage() instanceof Stage.Handling handling) {
            flags |= HANDLING;
            names[name + 1] = held(held, handling.processingGroup());
        }
        if (span.delivery().isPresent()) {
            flags |= DELIVERED;
            names[name + 2] = held(held, span.delivery().get().name());
        }
        names[name + 3] = held(held, span.threadName());
        if (span.outcome() instanceof Outcome.Failed failed) {
            flags |= FAILED;
            names[name + 4] = held(held, failed.failureType());
        }
        codes[2 * index + 1] = (byte) flags;
    }

    private void pack(int index, SinkSend send, Map<String, String> held) {
        var number = SEND_NUMBERS * index;
        packRef(sendNumbers, number, send.span());
        sendNumbers[number + 3] = send.startedAt().getEpochSecond();
        sendNanos[index] = send.startedAt().getNano();
        sendNames[2 * index] = held(held, send.event().name());
        sendNames[2 * index + 1] = held(held, send.delivery().name());
    }

    private MessageSpan span(int index) {
        var number = SPAN_NUMBERS * index;
        var name = SPAN_NAMES * index;
        var flags = codes[2 * index + 1];
        var type = new MessageType(KINDS[codes[2 * index]], names[name]);
        Stage stage = (flags & HANDLING) != 0 ? new Stage.Handling(type, names[name + 1]) : new Stage.Dispatch(type);
        var duration = Duration.ofSeconds(numbers[number + 9], nanos[2 * index + 1]);
        Outcome outcome = (flags & FAILED) != 0 ? new Outcome.Failed(duration, names[name + 4]) : new Outcome.Completed(duration);
        return new MessageSpan(ref(numbers, number),
            (flags & CONTINUES) != 0 ? Optional.of(ref(numbers, number + 3)) : Optional.empty(),
            new MessageId(new UUID(numbers[number + 6], numbers[number + 7])),
            stage,
            (flags & DELIVERED) != 0 ? Optional.of(new EventDelivery(names[name + 2])) : Optional.empty(),
            Instant.ofEpochSecond(numbers[number + 8], nanos[2 * index]),
            names[name + 3],
            outcome);
    }

    private SinkSend send(int index) {
        var number = SEND_NUMBERS * index;
        return new SinkSend(ref(sendNumbers, number), new MessageType(MessageKind.EVENT, sendNames[2 * index]), new EventDelivery(sendNames[2 * index + 1]),
            Instant.ofEpochSecond(sendNumbers[number + 3], sendNanos[index]));
    }

    private static void packRef(long[] numbers, int at, SpanRef span) {
        numbers[at] = span.traceHigh();
        numbers[at + 1] = span.traceLow();
        numbers[at + 2] = span.spanNumber();
    }

    private static SpanRef ref(long[] numbers, int at) {
        return new SpanRef(numbers[at], numbers[at + 1], numbers[at + 2]);
    }

    private static String held(Map<String, String> held, String name) {
        var kept = held.putIfAbsent(name, name);
        return kept == null ? name : kept;
    }
}
