package app.dodb.smd.monitor.hub.span;

import app.dodb.smd.api.event.delivery.EventDelivery;

import java.time.Instant;

import static java.util.Objects.requireNonNull;

// An application sending the publication of an event to one of its sinks, named by the delivery of the medium the sink writes to. The hub counts
// it as a sink route and does not retain it: what reads the event from the medium continues the publication, not the send.
public record SinkSend(SpanRef span, MessageType event, EventDelivery delivery, Instant startedAt) {

    public SinkSend {
        requireNonNull(span);
        requireNonNull(event);
        requireNonNull(delivery);
        requireNonNull(startedAt);
        if (event.kind() != MessageKind.EVENT) {
            throw new IllegalArgumentException("A sink send sends an event to delivery '" + delivery.name() + "'; got " + event.kind() + " " + event.name());
        }
    }
}
