package app.dodb.smd.monitor.hub;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

// A retained invocation with the summary of the parent the hub found for it, which may since have been evicted.
public record RetainedInvocation(Invocation invocation, Optional<ParentSummary> parent, boolean parentRetained) {

    public RetainedInvocation {
        requireNonNull(invocation);
        requireNonNull(parent);
        if (parentRetained && parent.isEmpty()) {
            throw new IllegalArgumentException("RetainedInvocation " + invocation.id().value() + " requires a parent to retain its parent");
        }
    }
}
