package app.dodb.smd.monitor.hub.span;

import static java.util.Objects.requireNonNull;

// Any host name or service instance ID OpenTelemetry allows; only its length is bounded, as the hub keeps it outside the memory budget.
public record InstanceName(String value) {

    public InstanceName {
        requireNonNull(value);
        if (value.isEmpty() || value.length() > 200) {
            throw new IllegalArgumentException("InstanceName requires 1 to 200 characters; got " + value.length() + " characters");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
