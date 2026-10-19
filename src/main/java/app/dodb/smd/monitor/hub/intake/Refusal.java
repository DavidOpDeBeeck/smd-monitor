package app.dodb.smd.monitor.hub.intake;

import static java.util.Objects.requireNonNull;

// The spans of a batch the hub refused, and why it refused the first of them.
public record Refusal(int spans, String reason) {

    public Refusal {
        requireNonNull(reason);
        if (spans < 1) {
            throw new IllegalArgumentException("A refusal refuses at least one span; got " + spans);
        }
    }
}
