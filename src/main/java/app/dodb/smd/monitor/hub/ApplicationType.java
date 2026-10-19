package app.dodb.smd.monitor.hub;

import app.dodb.smd.monitor.hub.span.ApplicationName;
import app.dodb.smd.monitor.hub.span.MessageType;

import static java.util.Objects.requireNonNull;

// A message type of one application: the same type name in two applications is two types.
public record ApplicationType(ApplicationName application, MessageType type) {

    public ApplicationType {
        requireNonNull(application);
        requireNonNull(type);
    }
}
