package app.dodb.smd.monitor.hub.span;

import static java.util.Objects.requireNonNull;

public record MessageType(MessageKind kind, String name) {

    public MessageType {
        requireNonNull(kind);
        requireNonNull(name);
        if (name.isBlank()) {
            throw new IllegalArgumentException("MessageType of kind " + kind + " requires a class name; got a blank name");
        }
    }
}
