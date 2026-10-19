package app.dodb.smd.monitor.hub;

import java.util.List;

import static java.util.Objects.requireNonNull;

// The retained occurrences of the failure causes a query asked for at a sequence number, in the order asked.
public record Failures(long sequence, List<FailureCause> causes) {

    public Failures {
        requireNonNull(causes);
    }
}
