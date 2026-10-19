package app.dodb.smd.monitor.hub.span;

import static java.util.Objects.requireNonNull;

public record SessionId(String value) {

    public SessionId {
        requireNonNull(value);
        if (value.isBlank() || value.length() > 200) {
            throw new IllegalArgumentException("SessionId requires 1 to 200 characters, not only whitespace; got '" + value + "'");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
