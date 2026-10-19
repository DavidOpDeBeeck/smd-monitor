package app.dodb.smd.monitor.hub.catalog;

import app.dodb.smd.api.event.delivery.EventDelivery;
import app.dodb.smd.monitor.hub.span.MessageKind;
import app.dodb.smd.monitor.hub.span.MessageType;

import static java.util.Objects.requireNonNull;

// An event the application published sent to one of its sinks, named by the delivery of the medium the sink writes to.
public record SinkRoute(MessageType event, EventDelivery delivery) {

    public SinkRoute {
        requireNonNull(event);
        requireNonNull(delivery);
        if (event.kind() != MessageKind.EVENT) {
            throw new IllegalArgumentException("A sink route leads an event to delivery '" + delivery.name() + "'; got " + event.kind() + " " + event.name());
        }
    }
}
