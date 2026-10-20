package app.dodb.smd.monitor.hub.span;

import static java.util.Objects.requireNonNull;

// Any service name OpenTelemetry and Spring allow; only its length is bounded, as the hub keeps it outside the memory budget and the page names it in
// every key and query of the application.
public record ApplicationName(String value) {

    public ApplicationName {
        requireNonNull(value);
        if (value.isEmpty() || value.length() > 255) {
            throw new IllegalArgumentException("ApplicationName requires 1 to 255 characters; got " + value.length() + " characters");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
