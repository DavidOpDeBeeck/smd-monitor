package app.dodb.smd.monitor.hub.catalog;

import app.dodb.smd.monitor.hub.span.ApplicationName;
import app.dodb.smd.monitor.hub.span.MessageKind;
import app.dodb.smd.monitor.hub.span.MessageType;

import static java.util.Objects.requireNonNull;

// A processing group handling an event the publisher published: the group's own application, or another application whose events reach
// the group, such as through a shared event store.
public record EventRoute(ApplicationName publisher, MessageType event, String processingGroup) {

    public EventRoute {
        requireNonNull(publisher);
        requireNonNull(event);
        requireNonNull(processingGroup);
        if (event.kind() != MessageKind.EVENT) {
            throw new IllegalArgumentException("A route leads an event to processing group '" + processingGroup + "'; got " + event.kind() + " " + event.name());
        }
    }
}
