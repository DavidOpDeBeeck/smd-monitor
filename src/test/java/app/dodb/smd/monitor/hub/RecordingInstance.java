package app.dodb.smd.monitor.hub;

import app.dodb.smd.api.event.delivery.EventDelivery;
import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.monitor.hub.intake.Refusal;
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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

final class RecordingInstance {

    static final ApplicationName TICKETS = new ApplicationName("ticket-service");
    static final ApplicationName BILLING = new ApplicationName("billing-service");
    static final MessageType OPEN = new MessageType(MessageKind.COMMAND, "OpenTicket");
    static final MessageType ASSIGN = new MessageType(MessageKind.COMMAND, "AssignTicket");
    static final MessageType OPENED = new MessageType(MessageKind.EVENT, "TicketOpened");
    static final MessageType AVAILABLE_AGENT = new MessageType(MessageKind.QUERY, "AvailableAgent");
    static final Instant STARTED_AT = Instant.parse("2026-01-01T12:00:00Z");

    final SessionId session = new SessionId(UUID.randomUUID().toString());
    final InstanceName name;
    private final ApplicationName application;

    RecordingInstance(ApplicationName application, String name) {
        this.application = application;
        this.name = new InstanceName(name);
    }

    Optional<Refusal> reportTo(SynchronousHub hub, MessageSpan... spans) {
        return hub.receive(batch(spans));
    }

    SpanBatch batch(MessageSpan... spans) {
        return new SpanBatch(application, name, session, List.of(spans), List.of());
    }

    Optional<Refusal> reportSendsTo(SynchronousHub hub, SinkSend... sends) {
        return hub.receive(new SpanBatch(application, name, session, List.of(), List.of(sends)));
    }

    // An entry point: a dispatch that continues no SMD invocation.
    MessageSpan dispatch(MessageType type) {
        return span(newTraceId(), Optional.empty(), MessageId.generate(), new Stage.Dispatch(type));
    }

    // The sending of an event's publication to a sink of the given delivery.
    SinkSend send(MessageType event, EventDelivery delivery) {
        return new SinkSend(SpanRef.of(newTraceId(), newSpanId()), event, delivery, STARTED_AT);
    }

    // A dispatch sent from the given invocation, which its span links to.
    MessageSpan dispatch(MessageType type, MessageSpan sender) {
        return span(sender.span().traceId(), Optional.of(sender.span()), MessageId.generate(), new Stage.Dispatch(type));
    }

    // A dispatch linking to a span the hub never receives.
    MessageSpan dispatchContinuingUnknown(MessageType type) {
        return span(newTraceId(), Optional.of(SpanRef.of(newTraceId(), newSpanId())), MessageId.generate(), new Stage.Dispatch(type));
    }

    MessageSpan handle(String processingGroup, MessageSpan publication) {
        return handle(processingGroup, EventDelivery.SYNC, publication);
    }

    MessageSpan handle(String processingGroup, EventDelivery delivery, MessageSpan publication) {
        return new MessageSpan(SpanRef.of(publication.span().traceId(), newSpanId()), Optional.of(publication.span()), publication.messageId(),
            new Stage.Handling(publication.stage().type(), processingGroup), Optional.of(delivery), STARTED_AT, name.value(),
            new Outcome.Completed(Duration.ofMillis(1)));
    }

    // Another application dispatching the same message with its original metadata, which links to the original dispatch.
    MessageSpan republish(MessageSpan publication) {
        return span(publication.span().traceId(), Optional.of(publication.span()), publication.messageId(), publication.stage());
    }

    MessageSpan republishWithoutMetadata(MessageSpan publication) {
        return span(newTraceId(), Optional.empty(), publication.messageId(), publication.stage());
    }

    // Started two minutes before the recorded spans, before the hub's window, so the Slowest reservoir does not keep it once Recent lets it go.
    static MessageSpan beforeTheWindow(MessageSpan span) {
        return with(span, STARTED_AT.minusSeconds(120), span.outcome());
    }

    static MessageSpan with(MessageSpan span, Instant startedAt, Outcome outcome) {
        return new MessageSpan(span.span(), span.continues(), span.messageId(), span.stage(), span.delivery(), startedAt, span.threadName(), outcome);
    }

    private MessageSpan span(String traceId, Optional<SpanRef> continues, MessageId messageId, Stage stage) {
        return new MessageSpan(SpanRef.of(traceId, newSpanId()), continues, messageId, stage, Optional.empty(), STARTED_AT, name.value(),
            new Outcome.Completed(Duration.ofMillis(1)));
    }

    private static String newSpanId() {
        return HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextLong());
    }

    private static String newTraceId() {
        var random = ThreadLocalRandom.current();
        return HexFormat.of().toHexDigits(random.nextLong()) + HexFormat.of().toHexDigits(random.nextLong());
    }

    static final class MutableClock extends Clock {

        // Late enough after the recorded spans that their seconds settled within the hub's window.
        private Instant now = STARTED_AT.plusSeconds(7);

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
