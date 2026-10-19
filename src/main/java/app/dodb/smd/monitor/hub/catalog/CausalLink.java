package app.dodb.smd.monitor.hub.catalog;

import app.dodb.smd.monitor.hub.span.MessageType;

import static java.util.Objects.requireNonNull;

public record CausalLink(MessageType parent, MessageType child) {

    public CausalLink {
        requireNonNull(parent);
        requireNonNull(child);
    }
}
