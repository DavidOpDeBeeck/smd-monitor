package app.dodb.smd.monitor.hub.catalog;

import app.dodb.smd.monitor.hub.span.MessageType;

import static java.util.Objects.requireNonNull;

// A message dispatched while the processing group handled an event.
public record GroupLink(String processingGroup, MessageType child) {

    public GroupLink {
        requireNonNull(processingGroup);
        requireNonNull(child);
    }
}
